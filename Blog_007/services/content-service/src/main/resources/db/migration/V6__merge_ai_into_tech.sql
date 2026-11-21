-- =====================================================================
-- V6: merge the AI section into TECH (data only).
--
-- WHY data only? The schema keeps 'AI' as an ALLOWED value in the CHECK
-- constraints of posts.section and feed_items.section:
--   * backward compatible: ingestion-service / assistant-service (separate
--     deploys, separate databases) may still say "AI" for a while. If we
--     dropped 'AI' from the constraint, one late event would fail to insert
--     and land in the dead-letter topic.
--   * rolling deploys: old content-service replicas keep running next to new
--     ones for a few minutes; they must not crash on the new schema.
-- The Java code maps AI -> TECH on every write (Section.canonical()), so after
-- this migration NO row uses 'AI'. A later migration can tighten the CHECK
-- once every producer has stopped sending it ("expand / contract" pattern).
--
-- Cost: one UPDATE per table. Fine at blog size (hundreds of rows). On a huge
-- table you would update in batches to avoid one long lock.
-- =====================================================================

-- updated_at / version change so caches and optimistic locking see the edit.
UPDATE posts
   SET section = 'TECH', updated_at = now(), version = version + 1
 WHERE section = 'AI';

UPDATE feed_items
   SET section = 'TECH'
 WHERE section = 'AI';
