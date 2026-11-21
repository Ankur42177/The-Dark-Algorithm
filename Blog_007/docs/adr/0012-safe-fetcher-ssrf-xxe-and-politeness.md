# 0012. Safe fetcher: SSRF, XXE, limits and politeness
Date: 2026-11-21 · Status: Accepted

## Context
ingestion-service downloads URLs chosen by others: feed URLs typed by the admin, redirect targets chosen
by remote sites, and XML bodies written by strangers. That is a textbook SSRF (cloud metadata at
169.254.169.254, internal ports, the Docker/Kubernetes network), XXE and resource-exhaustion surface
(PLAN §15). We must also be a polite client (PLAN §5.1): identify ourselves, honour robots.txt, avoid
refetching unchanged feeds.

## Options considered
1. **JDK `HttpClient` + URL string checks + `InetAddress` lookup before the request.** No dependency, but
   the validation lookup and the connect lookup are separate → DNS rebinding (TOCTOU); automatic redirects
   are all-or-nothing.
2. **Host allow-list** (only pre-approved domains). Strongest, but every new feed needs a config change,
   and redirects to CDNs/other hosts break.
3. **An egress proxy** (e.g. Smokescreen) that enforces the policy outside the app. Best for large
   fleets; one more component to run locally.
4. **Apache HttpClient 5 with a custom `DnsResolver`** that refuses blocked addresses, manual redirects,
   strict limits, and Rome with DOCTYPEs disallowed.

## Decision
Option 4, all outbound traffic through one `SafeFetcher`:
- **URL check** (`SsrfGuard.checkUrl`): http/https only, a host, no `user:pw@`, port absent/80/443 —
  applied before saving a source (400 Problem Details), before every request and every redirect hop.
- **Address check at connect time**: the connection pool resolves through `SsrfGuard.dnsResolver()`,
  which rejects if **any** resolved address is any-local, loopback, link-local (incl. 169.254.169.254),
  private, multicast, CGNAT 100.64/10, 192.0.0/24, 240/4 or IPv6 fc00::/7. Check and connect use the same
  lookup, so DNS rebinding doesn't apply. `pulse.fetch.allowed-private-hosts` exists for tests only.
- **Redirects** followed manually, max 3, each re-checked.
- **Limits**: connect 3 s, socket/response 5 s, one overall deadline while reading (defeats slow
  dribbling), body cap **5 MB** (`Content-Length` pre-check + counted while streaming, after
  decompression), 50 connections total / 4 per host, cookies disabled.
- **No automatic retries** (`disableAutomaticRetries()`): HttpClient 5's default retried 429/503 once
  after 1 s, doubling every RabbitMQ attempt (ADR-0011 owns retries).
- **XXE**: Rome `SyndFeedInput.setAllowDoctypes(false)` — any DOCTYPE is a permanent "not a valid feed".
- **Output hygiene**: only absolute http(s) item links (no `javascript:`/`data:`), HTML stripped, excerpt
  ≤ 300 chars, https image enclosures only, future dates clamped.
- **Politeness**: `User-Agent: PulseBot/1.0 (+https://github.com/Ankur42177/The-Dark-Algorithm)`;
  robots.txt (PulseBot group, else `*`; Disallow prefixes; cached 24 h in Redis; missing = allowed;
  disallowed → run `SKIPPED`); conditional GET with stored `ETag`/`Last-Modified` → `304` = `NOT_MODIFIED`;
  interval ≥ 15 min enforced by a CHECK constraint and `@Min(15)`.

## Measured
- `SafeFetcherTest`: 304 + User-Agent; 500 and a 4 s response (2 s budget) transient, given up in < 3.5 s;
  404 permanent; 50 MB body refused; 302 → 169.254.169.254 blocked; `file:`, `gopher:`, 127.0.0.1,
  10.0.0.5, `[::1]`, `user:pw@`, `:6379` refused before any request.
- `ParsingAndTextTest`: XXE payload rejected; `javascript:` link dropped; excerpt ≤ 300.
- `IngestionAdminIT`: dangerous URLs refused with 400 before saving.
- First real run: Sebastian Raschka's full-text newsletter feed exceeded the planned **2 MB** cap and went
  to the DLQ ("Feed too large"). Full-text newsletters of 2–5 MB are legitimate → cap raised to **5 MB**
  (still 10× below the 50 MB attack case).

## Consequences
- Good: SSRF defence sits where it can't be bypassed (the resolver); one choke point for all fetching;
  publishers mostly see tiny 304s.
- Bad: a deny-list must be kept complete (new special-use ranges); robots.txt support is minimal (no
  `Allow`, wildcards or `Crawl-delay`) — enough for feed URLs, not for a general crawler.
- Testing note: WireMock's Jetty gzips responses and rewrites strong ETags (`"v1"` → `"v1--gzip"`, correct
  HTTP); tests disable gzip so ETag assertions don't depend on the fake server. ETags are treated as opaque.
- Follow-ups: optional resolve-at-save for earlier admin feedback (never replacing the connect-time
  check); egress proxy / NetworkPolicy in Kubernetes as a second layer; honour `Retry-After` on 429.
