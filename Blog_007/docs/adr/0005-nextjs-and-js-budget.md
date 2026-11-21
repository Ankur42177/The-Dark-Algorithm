# 0005. Next.js for the website, and the JavaScript budget
Date: 2026-11-21 · Status: Accepted

## Context
PLAN §4.3 sets "< 90 KB gzipped JS on an article page" and §14.5 asks why Next.js and not Astro.
Measured on the Phase 2 build (Next.js 16.4.6, React 19.3.1, `next start`, gzip):
article page ≈ 140 KB gzipped JS (Lighthouse resource summary), of which react-dom ≈ 70 KB and the
Next.js router/runtime most of the rest. Our own components are a few KB.
Lighthouse (mobile emulation, local): Performance 100, LCP 0.8 s, CLS 0, TBT ≤ 10 ms.

## Options considered
1. **Next.js (App Router)** — static prerender + tag revalidation, React Server Components, first-class
   admin UI and streaming chat widget. Cons: ~140 KB framework baseline JS on every page.
2. **Astro** — zero JS by default (islands only), so reader pages could be < 10 KB JS. Cons: admin UI and
   chat widget need a separate React/Preact island setup; tag-based on-demand revalidation is less integrated.
3. **Next.js with a stripped client** (no client components on reader pages) — still ships the router runtime.

## Decision
Option 1. The 90 KB budget is **revised to 160 KB gzipped** for reader pages, with the hard rule that
the chatbot widget (Phase 8) and admin code (Phase 4) add **0 KB** until used. Lighthouse ≥ 95 stays.

## Consequences
- Good: one framework for reader pages, admin and chat; measured performance already at 100.
- Bad: framework JS is the main cost on slow phones; Astro would win this metric.
- Follow-up: re-measure every phase; consider Astro for reader pages if INP/LCP on real devices regress.
