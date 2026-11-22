-- =====================================================================
-- assistant_db V2: keep Harvey's vector index in sync with the site.
--   * image_url  → citation cards can show a thumbnail (post cover / article image)
--   * doc_hash   → SHA-256 of everything we embedded for a document. The reconciler compares it with the
--                  current content and SKIPS re-embedding unchanged documents (embedding costs time; a hash
--                  comparison costs nothing). Same idea as an HTTP ETag.
--   * section AI → TECH: content-service merged the AI section into TECH (its V6). Our copy follows.
-- Never edit an applied migration: changes always go into a NEW V<n> file.
-- =====================================================================
ALTER TABLE doc_chunks ADD COLUMN image_url varchar(600);
ALTER TABLE doc_chunks ADD COLUMN doc_hash  varchar(64);

UPDATE doc_chunks SET section = 'TECH' WHERE section = 'AI';

-- The reconciler deletes articles by URL ("is this link still in the feed?") — index it.
CREATE INDEX doc_chunks_url_idx ON doc_chunks (url);
