-- =====================================================================
-- Editorial curation, round 2 (2026-11-21).
--  1. The AI section merges INTO Tech: every AI source becomes TECH.
--  2. Tech "Around the web" = OFFICIAL COMPANY BLOGS ONLY. Individual
--     bloggers are paused (not deleted: their fetch history stays, and an
--     admin can re-enable one with a single PATCH).
--  3. New official AI/engineering blogs. Each URL was checked politely on
--     2026-11-21 (one request, PulseBot User-Agent): HTTP 200, valid RSS 2.0,
--     well under pulse.fetch.max-bytes (5 MB), robots.txt allows the path.
-- Football is untouched (V3 already paused the news wires).
-- =====================================================================

-- 1. AI → TECH. The CHECK constraint still allows 'AI' so older events/rows stay valid.
UPDATE feed_sources
   SET section = 'TECH'
 WHERE section = 'AI';

-- 2. Individual blogs → paused. Matched by feed_url (UNIQUE), exactly as V2/V3 left them.
UPDATE feed_sources
   SET active = false,
       disabled_reason = 'Curated 2026-11-21: individual blog; Tech shows official company blogs only'
 WHERE feed_url IN ('https://newsletter.pragmaticengineer.com/feed',     -- The Pragmatic Engineer
                    'https://blog.bytebytego.com/feed',                  -- ByteByteGo
                    'https://martinfowler.com/feed.atom',                -- Martin Fowler
                    'https://jvns.ca/atom.xml',                          -- Julia Evans
                    'https://simonwillison.net/atom/entries/',           -- Simon Willison (URL changed by V3)
                    'https://simonwillison.net/atom/everything/',        -- ... in case V3's rename was undone by hand
                    'https://lilianweng.github.io/index.xml',            -- Lilian Weng
                    'https://magazine.sebastianraschka.com/feed',        -- Sebastian Raschka (Ahead of AI)
                    'https://huyenchip.com/feed.xml',                    -- Chip Huyen
                    'https://karpathy.github.io/feed.xml');              -- Andrej Karpathy

-- Kept active (official company blogs, all TECH now): Netflix TechBlog, Cloudflare Blog,
-- Spring Blog, Hugging Face Blog. Nothing to change for them besides step 1.

-- 3. New official company blogs (verified 2026-11-21):
--    LangChain        200 application/rss+xml   100 items   ~79 KB
--    Claude           200 application/rss+xml    50 items   ~19 KB  (redirects to /resources/articles/rss.xml)
--    Google AI        200 application/xml        20 items   ~30 KB  (redirects to /innovation-and-ai/technology/ai/rss/)
--    Google Research  200 application/rss+xml   100 items   ~79 KB
--    Google DeepMind  200 application/xml       100 items   ~68 KB
-- SafeFetcher follows up to 3 redirects (each hop SSRF-checked), so the published URLs are stored as-is.
-- Hourly is plenty: these blogs post a few times a week.
INSERT INTO feed_sources (name, site_url, feed_url, section, fetch_interval_min, active) VALUES
  ('LangChain Blog',     'https://www.langchain.com/blog', 'https://www.langchain.com/blog/rss.xml', 'TECH', 60, true),
  ('Claude (Anthropic)', 'https://claude.com/blog',        'https://claude.com/blog/rss.xml',        'TECH', 60, true),
  ('Google AI',          'https://blog.google/technology/ai/', 'https://blog.google/technology/ai/rss/', 'TECH', 60, true),
  ('Google Research',    'https://research.google/blog/',  'https://research.google/blog/rss/',      'TECH', 60, true),
  ('Google DeepMind',    'https://deepmind.google/blog/',  'https://deepmind.google/blog/rss.xml',   'TECH', 60, true)
ON CONFLICT (feed_url) DO NOTHING;
