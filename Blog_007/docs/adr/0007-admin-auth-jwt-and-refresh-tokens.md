# 0007. Admin authentication: RS256 JWT in cookies, rotating refresh tokens in Postgres
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §15 asks for short-lived JWT access tokens, rotating refresh tokens, HttpOnly cookies, CSRF
protection, login throttling and a JWKS endpoint for the gateway. PLAN §10 suggested keeping refresh
tokens in Redis (`auth:refresh:{tokenId}`).

## Options considered
1. **Server sessions (JSESSIONID + Spring Session in Redis)** — simple revocation. Cons: every service and
   the gateway must share the session store; not the token-based design the plan teaches.
2. **JWT access + refresh tokens in Redis** — fast. Cons: our Redis is a fail-open, `allkeys-lfu`
   evicting cache (ADR-0006); refresh tokens and their reuse-detection state must not be evicted or lost.
3. **JWT access (RS256, 15 min) + opaque refresh tokens stored hashed in Postgres** with families for
   reuse detection.

## Decision
Option 3.
- Access token: RS256 JWT, 15 min, claims `iss, sub, iat, exp, jti, name, roles`; public keys at
  `/.well-known/jwks.json` (with `kid`), so verifiers can't mint tokens.
- Refresh token: 256 random bits, SHA-256 hash stored in `refresh_tokens`, single use; reuse of a used
  token revokes the whole family (`noRollbackFor` so the revocation survives the thrown exception).
- Cookies `HttpOnly; Secure; SameSite=Strict`, refresh cookie scoped to `Path=/api/v1/auth`; cookies are
  written with `HttpServletResponse.addHeader` so they don't replace the CSRF cookie.
- CSRF: Spring Security `csrf().spa()` double-submit; the bearer-token resolver reads only the
  `Authorization` header and a filter after `CsrfFilter` maps the cookie to a bearer token, so cookie
  requests are never exempt from CSRF.
- BCrypt via `DelegatingPasswordEncoder` (`{bcrypt}` prefix allows a later Argon2 migration); a real
  dummy hash equalises timing for unknown usernames; 5 failures / 15 min / IP throttle in Redis (fail-open).

## Consequences
- Good: revocation and theft detection that survive Redis outages; stateless access checks everywhere.
- Bad: an access token stays valid up to 15 min after logout (accepted; short TTL).
- Three defects were caught by `AuthAndAdminIT` before release (CSRF bypass, rolled-back revocation,
  dropped CSRF cookie) — kept as regression tests.
