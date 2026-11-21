-- =====================================================================
-- TEST-ONLY fixtures (src/test/resources: never packaged into the jar).
--
-- Why this file exists: PostApiIT, CachingIT and SearchAndInternalIT were
-- written against the dev seed (3 football posts + 1 draft + 5 tech). On
-- 2026-11-21 the dev seed was trimmed (real football articles are imported
-- through the admin API now), and those tests went red on main. Tests must
-- own their data instead of borrowing the developer's sample data — so the
-- rows they rely on live here, loaded by IntegrationTestBase via
-- spring.flyway.locations=…,classpath:db/testdata.
--
-- Runs after R__seed_sample_posts ("test fixtures" sorts after "seed sample
-- posts"), idempotent (ON CONFLICT DO NOTHING).
-- =====================================================================
INSERT INTO posts (slug, title, summary, body_md, body_html, section, status, reading_minutes, published_at)
VALUES
  ('arsenal-pressing-numbers', 'Arsenal''s press, in numbers',
   'PPDA, high turnovers and what the data says about this season.',
   E'# Pressing\n\nPasses allowed per defensive action (PPDA) is the classic pressing metric.',
   '<h1>Pressing</h1><p>Passes allowed per defensive action (PPDA) is the classic pressing metric.</p>',
   'FOOTBALL', 'PUBLISHED', 7, now() - interval '2 days'),
  ('expected-goals-for-beginners', 'Expected goals (xG) for beginners',
   'What xG measures, what it does not, and how to read it after a match.',
   E'# xG\n\nxG is the probability that a shot becomes a goal, based on thousands of similar shots.',
   '<h1>xG</h1><p>xG is the probability that a shot becomes a goal, based on thousands of similar shots.</p>',
   'FOOTBALL', 'PUBLISHED', 5, now() - interval '5 days'),
  ('the-best-football-data-sources', 'The best free football data sources',
   'Where analysts get event data without paying a fortune.',
   E'# Data sources\n\nOpen data releases are a great place to start learning.',
   '<h1>Data sources</h1><p>Open data releases are a great place to start learning.</p>',
   'FOOTBALL', 'PUBLISHED', 3, now() - interval '9 days'),
  ('draft-embeddings-deep-dive', 'Embeddings deep dive (draft)', 'Still writing this one.',
   E'# Embeddings\n\nTODO', '<h1>Embeddings</h1><p>TODO</p>',
   'TECH', 'DRAFT', 8, NULL)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO tags (name) VALUES ('arsenal'), ('analytics'), ('data') ON CONFLICT (name) DO NOTHING;

INSERT INTO post_tags (post_id, tag_id)
SELECT p.id, t.id
  FROM (VALUES ('arsenal-pressing-numbers', 'arsenal'), ('arsenal-pressing-numbers', 'analytics'),
               ('expected-goals-for-beginners', 'analytics'), ('the-best-football-data-sources', 'data')
       ) AS link(slug, tag)
  JOIN posts p ON p.slug = link.slug
  JOIN tags  t ON t.name = link.tag
ON CONFLICT DO NOTHING;
