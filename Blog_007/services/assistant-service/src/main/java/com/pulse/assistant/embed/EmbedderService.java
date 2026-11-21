package com.pulse.assistant.embed;

import com.pulse.assistant.content.ContentClient;
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
import java.util.HexFormat;
import java.util.List;

/**
 * Turns posts and articles into vectors in pgvector (the "R" in RAG = Retrieval).
 * <pre>
 *  post event ─► GET /api/v1/posts/{slug} ─► chunk ─► embed each chunk (nomic-embed-text, 768 numbers)
 *             ─► UPSERT doc_chunks (idempotent: same post → same rows)
 * </pre>
 * External articles: we only have title + a ≤300-char excerpt (fair-use rules, PLAN §5), so 1 chunk each.
 */
@Service
public class EmbedderService {

    private static final Logger log = LoggerFactory.getLogger(EmbedderService.class);

    private final EmbeddingModel embeddings;
    private final VectorStore vectors;
    private final ContentClient content;

    public EmbedderService(EmbeddingModel embeddings, VectorStore vectors, ContentClient content) {
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.content = content;
    }

    /** (Re-)embed one of Ankur's posts. Returns the number of chunks, 0 if the post isn't published. */
    public int indexPost(String postId, String slug) {
        var post = content.post(slug);
        if (post.isEmpty()) {
            if (postId != null) {
                vectors.deleteDocument("POST", postId);            // not public (any more): forget it
            }
            return 0;
        }
        var p = post.get();
        String id = p.id() != null ? p.id() : postId;
        List<String> texts = new ArrayList<>(Chunker.chunkHtml(p.bodyHtml()));
        if (texts.isEmpty() || p.summary() != null) {
            texts.add(0, p.title() + (p.summary() == null ? "" : " — " + p.summary()));   // chunk 0: title + summary
        }
        // nomic-embed-text expects a task prefix: "search_document:" for stored text, "search_query:" for questions.
        List<float[]> vecs = embeddings.embed(texts.stream().map(t -> "search_document: " + p.title() + "\n" + t).toList());
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new Chunk("POST", id, i, p.title(), "/posts/" + p.slug(), p.section(), "Pulse", texts.get(i), vecs.get(i), p.publishedAt()));
        }
        vectors.replaceDocument("POST", id, chunks);
        log.info("embedded post {} into {} chunks", p.slug(), chunks.size());
        return chunks.size();
    }

    public int deletePost(String postId) {
        return vectors.deleteDocument("POST", postId);
    }

    public void indexArticle(String articleId, String url, String title, String excerpt, String section, String source, Instant publishedAt) {
        String id = articleId != null ? articleId : sha256(url).substring(0, 32);
        String text = title + (excerpt == null ? "" : "\n" + excerpt);
        float[] v = embeddings.embed("search_document: " + text);
        vectors.replaceDocument("ARTICLE", id, List.of(new Chunk("ARTICLE", id, 0, title, url, section,
                source == null ? "web" : source, text, v, publishedAt)));
    }

    /** Embed every published post (works before Kafka exists, and repairs the index after outages). */
    public int backfill() {
        int chunks = 0;
        for (var item : content.allPublishedPosts()) {
            try {
                chunks += indexPost(item.id(), item.slug());
            } catch (RuntimeException e) {
                log.warn("backfill: could not embed {}: {}", item.slug(), e.getMessage());
            }
        }
        return chunks;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
