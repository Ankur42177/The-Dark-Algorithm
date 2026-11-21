-- =====================================================================
-- Sample data for LOCAL DEVELOPMENT ONLY (loaded only with the "dev" profile).
--
-- "R__" = a REPEATABLE migration: Flyway re-runs it whenever the file's
-- checksum changes, after all V__ migrations. So it must be safe to run many
-- times: every INSERT uses ON CONFLICT DO NOTHING ("idempotent").
--
-- V6 merged the AI section into TECH, so the former AI samples are TECH here too
-- (this file runs AFTER V6 on a fresh database and would otherwise re-create AI rows).
--
-- 2026-11-21: the football placeholders and the empty "embeddings (draft)" were removed: real football content
-- comes from Ankur's FootballHub articles (imported via the admin API), and DO NOTHING means editing this file
-- re-runs it, which would otherwise re-create posts an admin had deleted. On an existing database the TECH rows
-- below are no-ops (the real, full-length articles already own these slugs).
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

  ('rag-in-one-picture',
   'RAG in one picture',
   'Retrieval-augmented generation: search first, then let the model answer with sources.',
   E'# RAG\n\nEmbed your documents, retrieve the closest chunks, put them in the prompt.',
   '<h1>RAG</h1><p>Embed your documents, retrieve the closest chunks, put them in the prompt.</p>',
   'TECH', 'PUBLISHED', 6, now() - interval '12 hours'),

  ('what-is-an-ai-agent',
   'What is an AI agent, really?',
   'An LLM in a loop that can call tools, read results and decide what to do next.',
   E'# Agents\n\nModel -> tool call -> result -> model -> answer.',
   '<h1>Agents</h1><p>Model -&gt; tool call -&gt; result -&gt; model -&gt; answer.</p>',
   'TECH', 'PUBLISHED', 5, now() - interval '4 days')
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
