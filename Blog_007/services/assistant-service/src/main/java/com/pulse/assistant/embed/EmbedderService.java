package com.pulse.assistant.embed;

import com.pulse.assistant.content.ContentClient;
import com.pulse.assistant.content.ContentClient.ContentItem;
import com.pulse.assistant.search.VectorStore;
import com.pulse.assistant.search.VectorStore.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Turns posts and articles into vectors in pgvector (the "R" in RAG = Retrieval).
 * <pre>
 *  post event ─► GET /api/v1/posts/{slug} ─► chunk ─► embed each chunk (nomic-embed-text, 768 numbers)
 *             ─► UPSERT doc_chunks (idempotent: same post → same rows)
 * </pre>
 * External articles: we only have title + a ≤300-char excerpt (fair-use rules, PLAN §5), so 1 chunk each.
 *
 * <p><b>Two ways to stay in sync</b> (both idempotent, both safe to run at any time):
 * <ol>
 *   <li><b>Events</b> (Kafka, seconds): published/updated → re-embed, unpublished/deleted → delete.</li>
 *   <li><b>Reconcile</b> (startup + every 30 min + POST /internal/v1/embed/backfill): compare the index with what the
 *       site shows RIGHT NOW. Embeds what's missing or changed, deletes what's gone. This catches everything events
 *       can't: content imported straight into the database, feed items removed without an event, an outage of the
 *       embedder. A content HASH per document makes re-runs cheap: unchanged documents are skipped.</li>
 * </ol>
 * This "event-driven + periodic reconciliation" pair is the same pattern Kubernetes controllers use.
 */
@Service
public class EmbedderService {

    /** Result of one reconcile run, shown by /internal/v1/embed/stats. */
    public record ReconcileReport(Instant startedAt, long ms, boolean complete,
                                  int postsOnSite, int postsEmbedded, int postsUnchanged, int postsDeleted,
                                  int articlesOnSite, int articlesEmbedded, int articlesUnchanged, int articlesDeleted,
                                  int chunksWritten, int failures, String note) {
    }

    private static final Logger log = LoggerFactory.getLogger(EmbedderService.class);
    private static final int ARTICLE_BATCH = 32;

    private final EmbeddingModel embeddings;
    private final VectorStore vectors;
    private final ContentClient content;
    /** Only one reconcile at a time (startup run + scheduled run + a manual backfill could overlap). */
    private final ReentrantLock reconcileLock = new ReentrantLock();
    private volatile ReconcileReport lastReport;

    public EmbedderService(EmbeddingModel embeddings, VectorStore vectors, ContentClient content) {
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.content = content;
    }

    // ------------------------------------------------------------------ posts

    /** (Re-)embed one of Ankur's posts. Returns the number of chunks written, 0 if unpublished or unchanged. */
    public int indexPost(String postId, String slug) {
        return indexPost(postId, slug, false);
    }

    int indexPost(String postId, String slug, boolean onlyIfChanged) {
        var post = slug == null ? java.util.Optional.<ContentClient.PostBody>empty() : content.post(slug);
        if (post.isEmpty()) {
            if (postId != null) {
                vectors.deleteDocument("POST", postId);            // not public (any more): forget it
            }
            return 0;
        }
        var p = post.get();
        String id = p.id() != null ? p.id() : postId;
        String hash = sha256(String.join("\u0001", nz(p.slug()), nz(p.title()), nz(p.summary()), nz(p.section()),
                nz(p.coverImageUrl()), String.join(",", p.tags()), nz(p.bodyHtml())));
        if (onlyIfChanged && hash.equals(vectors.documentHashes("POST").get(id))) {
            return 0;
        }
        List<String> texts = new ArrayList<>(Chunker.chunkHtml(p.bodyHtml()));
        // chunk 0: title + summary + tags — matches "what is this post about?" style questions
        texts.add(0, p.title() + (p.summary() == null ? "" : " — " + p.summary())
                + (p.tags().isEmpty() ? "" : "\nTopics: " + String.join(", ", p.tags())));
        String label = sectionLabel(p.section());
        // nomic-embed-text expects a task prefix: "search_document:" for stored text, "search_query:" for questions.
        // We also prepend the section and title so every chunk "knows" what it belongs to.
        List<float[]> vecs = embeddings.embed(texts.stream()
                .map(t -> "search_document: " + label + " · " + p.title() + "\n" + t).toList());
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new Chunk("POST", id, i, p.title(), "/posts/" + p.slug(), p.section(), "Pulse", texts.get(i), vecs.get(i),
                    p.publishedAt(), p.coverImageUrl(), hash));
        }
        vectors.replaceDocument("POST", id, chunks);
        log.info("embedded post {} into {} chunks", p.slug(), chunks.size());
        return chunks.size();
    }

    public int deletePost(String postId) {
        return postId == null ? 0 : vectors.deleteDocument("POST", postId);
    }

    // ------------------------------------------------------------------ external articles

    /** Stable id of an external article: SHA-256 of its canonical URL (= ingestion's urlHash). */
    public static String articleId(String url) {
        return sha256(url);
    }

    public void indexArticle(String articleId, String url, String title, String excerpt, String section, String source,
                             Instant publishedAt, String imageUrl) {
        ContentItem item = new ContentItem("ARTICLE", null, null, title, excerpt, ContentClient.canonicalSection(section), url,
                source == null ? "web" : source, publishedAt, imageUrl);
        embedArticles(List.of(item), articleId != null ? List.of(articleId) : null);
    }

    /** Embed a batch of articles in ONE call to the model (much faster than one call per article). */
    private int embedArticles(List<ContentItem> items, List<String> ids) {
        List<String> inputs = new ArrayList<>();
        for (ContentItem a : items) {
            inputs.add("search_document: " + a.source() + " · " + sectionLabel(a.section()) + "\n" + articleText(a));
        }
        List<float[]> vecs = embeddings.embed(inputs);
        for (int i = 0; i < items.size(); i++) {
            ContentItem a = items.get(i);
            String id = ids != null ? ids.get(i) : articleId(a.url());
            vectors.replaceDocument("ARTICLE", id, List.of(new Chunk("ARTICLE", id, 0, a.title(), a.url(), a.section(), a.source(),
                    articleText(a), vecs.get(i), a.publishedAt(), a.imageUrl(), articleHash(a))));
        }
        return items.size();
    }

    private static String articleText(ContentItem a) {
        return a.title() + (a.summary() == null || a.summary().isBlank() ? "" : "\n" + a.summary());
    }

    private static String articleHash(ContentItem a) {
        return sha256(String.join("\u0001", nz(a.url()), nz(a.title()), nz(a.summary()), nz(a.source()), nz(a.section()),
                nz(a.imageUrl()), String.valueOf(a.publishedAt())));
    }

    // ------------------------------------------------------------------ reconcile

    /** Bring the whole index in line with the site. {@code force} re-embeds even unchanged documents. */
    public ReconcileReport reconcile(boolean force) {
        if (!reconcileLock.tryLock()) {
            return new ReconcileReport(Instant.now(), 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "another reconcile is already running");
        }
        try {
            return doReconcile(force);
        } finally {
            reconcileLock.unlock();
        }
    }

    private ReconcileReport doReconcile(boolean force) {
        Instant started = Instant.now();
        long t0 = System.currentTimeMillis();
        int pEmb = 0, pSame = 0, pDel = 0, aEmb = 0, aSame = 0, aDel = 0, chunks = 0, failures = 0, postsOnSite = 0, articlesOnSite = 0;
        List<String> notes = new ArrayList<>();

        // --- posts
        var posts = content.allPublishedPosts();
        if (posts.isPresent()) {
            postsOnSite = posts.get().size();
            Map<String, String> stored = vectors.documentHashes("POST");
            Set<String> live = new HashSet<>();
            for (ContentItem item : posts.get()) {
                live.add(item.id());
                try {
                    int n = indexPost(item.id(), item.slug(), !force && stored.containsKey(item.id()));
                    if (n > 0) {
                        pEmb++;
                        chunks += n;
                    } else {
                        pSame++;
                    }
                } catch (RuntimeException e) {
                    failures++;
                    log.warn("reconcile: could not embed post {}: {}", item.slug(), e.getMessage());
                }
            }
            List<String> gone = stored.keySet().stream().filter(id -> !live.contains(id)).toList();
            pDel = gone.size();
            vectors.deleteDocuments("POST", gone);
        } else {
            notes.add("posts skipped: content-service unavailable");
        }

        // --- external articles
        var feed = content.allFeedItems();
        if (feed.isPresent()) {
            articlesOnSite = feed.get().size();
            Map<String, String> stored = vectors.documentHashes("ARTICLE");
            Set<String> live = new HashSet<>();
            List<ContentItem> todo = new ArrayList<>();
            for (ContentItem a : feed.get()) {
                if (a.url() == null || a.title() == null) {
                    continue;
                }
                String id = articleId(a.url());
                if (!live.add(id)) {
                    continue;                                          // same URL twice in the feed
                }
                if (!force && articleHash(a).equals(stored.get(id))) {
                    aSame++;
                } else {
                    todo.add(a);
                }
            }
            for (int i = 0; i < todo.size(); i += ARTICLE_BATCH) {
                List<ContentItem> batch = todo.subList(i, Math.min(todo.size(), i + ARTICLE_BATCH));
                try {
                    aEmb += embedArticles(batch, null);
                } catch (RuntimeException e) {
                    failures += batch.size();
                    log.warn("reconcile: article batch {} failed: {}", i / ARTICLE_BATCH, e.getMessage());
                }
            }
            chunks += aEmb;
            List<String> gone = stored.keySet().stream().filter(id -> !live.contains(id)).toList();
            aDel = gone.size();
            vectors.deleteDocuments("ARTICLE", gone);
        } else {
            notes.add("articles skipped: content-service unavailable");
        }

        ReconcileReport r = new ReconcileReport(started, System.currentTimeMillis() - t0, posts.isPresent() && feed.isPresent(),
                postsOnSite, pEmb, pSame, pDel, articlesOnSite, aEmb, aSame, aDel, chunks, failures,
                notes.isEmpty() ? null : String.join("; ", notes));
        lastReport = r;
        log.info("reconcile done: {}", r);
        return r;
    }

    public ReconcileReport lastReport() {
        return lastReport;
    }

    /** Old name, kept for callers/tests: reconcile without forcing; returns chunks written. */
    public int backfill() {
        return reconcile(false).chunksWritten();
    }

    // ------------------------------------------------------------------ helpers

    static String sectionLabel(String section) {
        return "FOOTBALL".equals(section) ? "Football" : "Code & Cortex (tech)";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
