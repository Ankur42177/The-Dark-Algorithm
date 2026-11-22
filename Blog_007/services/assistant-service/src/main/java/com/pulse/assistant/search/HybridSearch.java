package com.pulse.assistant.search;

import com.pulse.assistant.config.AssistantProperties;
import com.pulse.assistant.content.ContentClient;
import com.pulse.assistant.content.ContentClient.ContentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HYBRID search = semantic (meaning) + lexical (exact words), fused with RRF.
 * <ul>
 *   <li>Semantic: embed the question, find the closest chunks in pgvector. Finds "consumer groups" for
 *       "how do several Kafka readers share work?" even without shared words. Covers Ankur's posts AND the
 *       external articles (title + excerpt) — content-service's full-text search only knows posts.</li>
 *   <li>Lexical: Postgres full-text in content-service. Great for names and rare terms ("Rodri", "PPDA").</li>
 * </ul>
 * If the embedding model is down or SLOW (a busy laptop!), we give up on it after a short timeout and still
 * return the lexical half (graceful degradation): a search must never wait 30 s for a vector.
 */
@Service
public class HybridSearch {

    /** How much of the best-matching chunk we hand to the model (≈ 200 tokens): enough to answer from. */
    static final int SNIPPET_CHARS = 700;
    /** The two best matches get (almost) their whole chunk: they usually hold the answer. */
    static final int TOP_SNIPPET_CHARS = 1800;

    private static final Logger log = LoggerFactory.getLogger(HybridSearch.class);

    private final EmbeddingModel embeddings;
    private final VectorStore vectors;
    private final ContentClient content;
    private final ExecutorService executor;
    private final AssistantProperties props;

    public HybridSearch(EmbeddingModel embeddings, VectorStore vectors, ContentClient content, ExecutorService chatExecutor,
                        AssistantProperties props) {
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.content = content;
        this.executor = chatExecutor;
        this.props = props;
    }

    public List<ContentItem> search(String query, String section, int limit) {
        List<ContentItem> semantic = List.of();
        try {
            // CompletableFuture = "a result that will arrive later". We run the embedding call on another (virtual)
            // thread and wait at most embedTimeout for it.
            float[] q = CompletableFuture.supplyAsync(() -> embeddings.embed("search_query: " + query), executor)
                    .get(props.embedTimeoutOrDefault().toMillis(), TimeUnit.MILLISECONDS);
            List<VectorStore.Match> matches = vectors.nearest(q, section, limit);
            List<ContentItem> items = new java.util.ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                items.add(toItem(matches.get(i), i < 2 ? TOP_SNIPPET_CHARS : SNIPPET_CHARS));   // the best two get more text
            }
            semantic = items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Vector search unavailable ({}); using full-text only", e.toString());
        }
        List<ContentItem> lexical = content.fullTextSearch(query, section, limit);
        // Semantic list first: when both lists find the same post, we keep the semantic copy (it carries the
        // best-matching CHUNK, which answers the question better than the post's summary).
        return Rrf.fuse(ContentItem::url, limit, semantic, lexical);
    }

    private static ContentItem toItem(VectorStore.Match m, int max) {
        String slug = m.url().startsWith("/posts/") ? m.url().substring("/posts/".length()) : null;
        String snippet = m.content().length() > max ? m.content().substring(0, max) + "…" : m.content();
        return new ContentItem(m.docType(), m.docId(), slug, m.title(), snippet, m.section(), m.url(), m.source(), m.publishedAt(), m.imageUrl());
    }
}
