-- =====================================================================
-- Phase 1 "break it" helper: insert 100,000 fake PUBLISHED posts so that
-- index vs no-index and OFFSET vs keyset differences become visible.
--
-- Run (from Blog_007/):
--   docker exec -i pulse-postgres psql -U content -d content_db < scripts/load-100k-posts.sql
-- Remove them again:
--   docker exec -i pulse-postgres psql -U content -d content_db -c "DELETE FROM posts WHERE slug LIKE 'fake-%';"
-- =====================================================================
INSERT INTO posts (slug, title, summary, body_md, body_html, section, status, reading_minutes, published_at)
SELECT 'fake-' || g,
       'Fake post number ' || g,
       'Generated for load testing',
       'Body ' || g,
       '<p>Body ' || g || '</p>',
       (ARRAY['TECH', 'FOOTBALL', 'AI'])[1 + (g % 3)],
       'PUBLISHED',
       1 + (g % 10),
       now() - (g || ' minutes')::interval          -- one post per minute going back ~70 days
  FROM generate_series(1, 100000) AS g
ON CONFLICT (slug) DO NOTHING;

ANALYZE posts;   -- refresh the planner's statistics so it knows the table is now big
