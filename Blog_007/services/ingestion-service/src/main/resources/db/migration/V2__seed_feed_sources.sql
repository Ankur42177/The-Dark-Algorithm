-- =====================================================================
-- Seed sources from PLAN §5.2. Every URL was checked politely on 2026-11-21
-- (PulseBot User-Agent, one request each). Failing ones start INACTIVE with
-- the reason, so an admin can fix the URL and re-enable them.
-- Rules (PLAN §5.1): official feeds only, metadata + <=300-char excerpt,
-- always link out, fetch no more often than every 15-30 minutes.
-- =====================================================================
INSERT INTO feed_sources (name, site_url, feed_url, section, fetch_interval_min, active, disabled_reason) VALUES
  ('The Pragmatic Engineer', 'https://newsletter.pragmaticengineer.com', 'https://newsletter.pragmaticengineer.com/feed', 'TECH', 30, true, NULL),
  ('ByteByteGo', 'https://blog.bytebytego.com', 'https://blog.bytebytego.com/feed', 'TECH', 30, true, NULL),
  ('Martin Fowler', 'https://martinfowler.com', 'https://martinfowler.com/feed.atom', 'TECH', 30, true, NULL),
  ('Netflix TechBlog', 'https://netflixtechblog.com', 'https://netflixtechblog.com/feed', 'TECH', 30, true, NULL),
  ('Cloudflare Blog', 'https://blog.cloudflare.com', 'https://blog.cloudflare.com/rss/', 'TECH', 30, true, NULL),
  ('Julia Evans', 'https://jvns.ca', 'https://jvns.ca/atom.xml', 'TECH', 30, true, NULL),
  ('Spring Blog', 'https://spring.io/blog', 'https://spring.io/blog.atom', 'TECH', 30, true, NULL),
  ('Simon Willison', 'https://simonwillison.net', 'https://simonwillison.net/atom/everything/', 'AI', 30, true, NULL),
  ('Lilian Weng', 'https://lilianweng.github.io', 'https://lilianweng.github.io/index.xml', 'AI', 30, true, NULL),
  ('Sebastian Raschka (Ahead of AI)', 'https://magazine.sebastianraschka.com', 'https://magazine.sebastianraschka.com/feed', 'AI', 30, true, NULL),
  ('Chip Huyen', 'https://huyenchip.com', 'https://huyenchip.com/feed.xml', 'AI', 30, true, NULL),
  ('Andrej Karpathy', 'https://karpathy.github.io', 'https://karpathy.github.io/feed.xml', 'AI', 30, true, NULL),
  ('Hugging Face Blog', 'https://huggingface.co/blog', 'https://huggingface.co/blog/feed.xml', 'AI', 30, true, NULL),
  ('The Guardian - Football', 'https://www.theguardian.com/football', 'https://www.theguardian.com/football/rss', 'FOOTBALL', 15, true, NULL),
  ('BBC Sport - Football', 'https://www.bbc.co.uk/sport/football', 'https://feeds.bbci.co.uk/sport/football/rss.xml', 'FOOTBALL', 15, true, NULL),
  ('StatsBomb', 'https://statsbomb.com', 'https://statsbomb.com/feed/', 'FOOTBALL', 30, false, 'Verified 2026-11-21: feed URL returns an HTML page, not RSS/Atom'),
  ('Football365', 'https://www.football365.com', 'https://www.football365.com/feed', 'FOOTBALL', 30, false, 'Verified 2026-11-21: feed URL returns HTTP 404')
ON CONFLICT (feed_url) DO NOTHING;
