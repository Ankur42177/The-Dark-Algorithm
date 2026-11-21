-- =====================================================================
-- Editorial curation (2026-11-21), after the first real ingestion run.
-- Goal: "Around the web" shows analysis and long reads, not a news wire.
-- Every new URL was checked politely (one request, PulseBot User-Agent).
-- =====================================================================

-- News wires → paused. Mostly match reports, live blogs, quizzes and podcasts;
-- Pulse's football section is analysis (Ankur's own FootballHub articles).
UPDATE feed_sources
   SET active = false,
       disabled_reason = 'Curated 2026-11-21: breaking-news feed (live blogs, quizzes, podcasts), not analysis'
 WHERE feed_url IN ('https://feeds.bbci.co.uk/sport/football/rss.xml',
                    'https://www.theguardian.com/football/rss');

-- Simon Willison: long-form entries only (the "everything" feed also has one-line quotes and link posts).
-- New URL → forget the old feed's ETag/Last-Modified, fetch soon.
UPDATE feed_sources
   SET feed_url = 'https://simonwillison.net/atom/entries/',
       etag = NULL, last_modified = NULL, next_fetch_at = now()
 WHERE feed_url = 'https://simonwillison.net/atom/everything/';

-- Sebastian Raschka's full-text newsletter is ~2-5 MB: allowed again now that pulse.fetch.max-bytes = 5 MB.
UPDATE feed_sources
   SET next_fetch_at = now(), consecutive_failures = 0
 WHERE feed_url = 'https://magazine.sebastianraschka.com/feed';

-- Football analysis sources (verified: valid RSS, current posts, < 5 MB).
INSERT INTO feed_sources (name, site_url, feed_url, section, fetch_interval_min, active) VALUES
  ('Spielverlagerung', 'https://spielverlagerung.com', 'https://spielverlagerung.com/feed/', 'FOOTBALL', 60, true),
  ('Jonathan Wilson (The Guardian)', 'https://www.theguardian.com/profile/jonathanwilson',
   'https://www.theguardian.com/profile/jonathanwilson/rss', 'FOOTBALL', 60, true)
ON CONFLICT (feed_url) DO NOTHING;
