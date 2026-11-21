-- =====================================================================
-- Sample data for LOCAL DEVELOPMENT ONLY (loaded only with the "dev" profile).
--
-- "R__" = a REPEATABLE migration: Flyway re-runs it whenever the file's
-- checksum changes, after all V__ migrations. So it must be safe to run many
-- times: every INSERT uses ON CONFLICT DO NOTHING ("idempotent").
-- =====================================================================

INSERT INTO posts (slug, title, summary, body_md, body_html, section, status, reading_minutes, published_at)
VALUES
  ('kafka-consumer-groups-explained',
   'Kafka consumer groups, explained with letters and postmen',
   'How Kafka splits work between consumers, and why partitions cap your parallelism.',
   E'# Consumer groups\n\nEvery **group** gets every event; inside a group each partition goes to one consumer.',
   '<h1>Consumer groups</h1><p>Every <strong>group</strong> gets every event; inside a group each partition goes to one consumer.</p>',
   'TECH', 'PUBLISHED', 6, now() - interval '1 day'),

  ('why-keyset-pagination-beats-offset',
   'Why keyset pagination beats OFFSET',
   'OFFSET 100000 reads and throws away 100,000 rows. A cursor jumps straight to the right place.',
   E'# Keyset pagination\n\nUse `WHERE (published_at, id) < (?, ?)` instead of `OFFSET`.',
   '<h1>Keyset pagination</h1><p>Use <code>WHERE (published_at, id) &lt; (?, ?)</code> instead of <code>OFFSET</code>.</p>',
   'TECH', 'PUBLISHED', 5, now() - interval '3 days'),

  ('redis-is-single-threaded-and-fast',
   'Redis is single-threaded. So why is it so fast?',
   'Memory, an event loop and simple data structures.',
   E'# Redis speed\n\nNo locks, no disk on the hot path, and O(1) operations.',
   '<h1>Redis speed</h1><p>No locks, no disk on the hot path, and O(1) operations.</p>',
   'TECH', 'PUBLISHED', 4, now() - interval '6 days'),

  ('arsenal-pressing-numbers',
   'Arsenal''s press, in numbers',
   'PPDA, high turnovers and what the data says about this season.',
   E'# Pressing\n\nPasses allowed per defensive action (PPDA) is the classic pressing metric.',
   '<h1>Pressing</h1><p>Passes allowed per defensive action (PPDA) is the classic pressing metric.</p>',
   'FOOTBALL', 'PUBLISHED', 7, now() - interval '2 days'),

  ('expected-goals-for-beginners',
   'Expected goals (xG) for beginners',
   'What xG measures, what it does not, and how to read it after a match.',
   E'# xG\n\nxG is the probability that a shot becomes a goal, based on thousands of similar shots.',
   '<h1>xG</h1><p>xG is the probability that a shot becomes a goal, based on thousands of similar shots.</p>',
   'FOOTBALL', 'PUBLISHED', 5, now() - interval '5 days'),

  ('the-best-football-data-sources',
   'The best free football data sources',
   'Where analysts get event data without paying a fortune.',
   E'# Data sources\n\nOpen data releases are a great place to start learning.',
   '<h1>Data sources</h1><p>Open data releases are a great place to start learning.</p>',
   'FOOTBALL', 'PUBLISHED', 3, now() - interval '9 days'),

  ('rag-in-one-picture',
   'RAG in one picture',
   'Retrieval-augmented generation: search first, then let the model answer with sources.',
   E'# RAG\n\nEmbed your documents, retrieve the closest chunks, put them in the prompt.',
   '<h1>RAG</h1><p>Embed your documents, retrieve the closest chunks, put them in the prompt.</p>',
   'AI', 'PUBLISHED', 6, now() - interval '12 hours'),

  ('what-is-an-ai-agent',
   'What is an AI agent, really?',
   'An LLM in a loop that can call tools, read results and decide what to do next.',
   E'# Agents\n\nModel -> tool call -> result -> model -> answer.',
   '<h1>Agents</h1><p>Model -&gt; tool call -&gt; result -&gt; model -&gt; answer.</p>',
   'AI', 'PUBLISHED', 5, now() - interval '4 days'),

  ('draft-embeddings-deep-dive',
   'Embeddings deep dive (draft)',
   'Still writing this one.',
   E'# Embeddings\n\nTODO',
   '<h1>Embeddings</h1><p>TODO</p>',
   'AI', 'DRAFT', 8, NULL)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO tags (name)
VALUES ('kafka'), ('databases'), ('redis'), ('performance'), ('arsenal'), ('analytics'),
       ('data'), ('rag'), ('llm'), ('agents')
ON CONFLICT (name) DO NOTHING;

-- Link posts to tags by name (VALUES list = a tiny inline table).
INSERT INTO post_tags (post_id, tag_id)
SELECT p.id, t.id
  FROM (VALUES
          ('kafka-consumer-groups-explained',      'kafka'),
          ('why-keyset-pagination-beats-offset',   'databases'),
          ('why-keyset-pagination-beats-offset',   'performance'),
          ('redis-is-single-threaded-and-fast',    'redis'),
          ('redis-is-single-threaded-and-fast',    'performance'),
          ('arsenal-pressing-numbers',             'arsenal'),
          ('arsenal-pressing-numbers',             'analytics'),
          ('expected-goals-for-beginners',         'analytics'),
          ('the-best-football-data-sources',       'data'),
          ('rag-in-one-picture',                   'rag'),
          ('rag-in-one-picture',                   'llm'),
          ('what-is-an-ai-agent',                  'agents'),
          ('what-is-an-ai-agent',                  'llm')
       ) AS link(slug, tag)
  JOIN posts p ON p.slug = link.slug
  JOIN tags  t ON t.name = link.tag
ON CONFLICT DO NOTHING;
