package com.pulse.assistant.search;

import com.pulse.assistant.content.ContentClient;
import com.pulse.assistant.content.ContentClient.ContentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * HYBRID search = semantic (meaning) + lexical (exact words), fused with RRF.
 * <ul>
 *   <li>Semantic: embed the question, find the closest chunks in pgvector. Finds "consumer groups" for
 *       "how do several Kafka readers share work?" even without shared words.</li>
 *   <li>Lexical: Postgres full-text in content-service. Great for names and rare terms ("Arsenal", "PPDA").</li>
 * </ul>
 * If the embedding model is down, we still return the lexical half (graceful degradation).
 */
@Service
public class HybridSearch {

    private static final Logger log = LoggerFactory.getLogger(HybridSearch.class);

    private final EmbeddingModel embeddings;
    private final VectorStore vectors;
    private final ContentClient content;

    public HybridSearch(EmbeddingModel embeddings, VectorStore vectors, ContentClient content) {
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.content = content;
    }

    public List<ContentItem> search(String query, String section, int limit) {
        List<ContentItem> semantic = List.of();
        try {
            float[] q = embeddings.embed("search_query: " + query);     // nomic-embed-text task prefix
            semantic = vectors.nearest(q, section, limit).stream().map(HybridSearch::toItem).toList();
        } catch (RuntimeException e) {
            log.warn("Vector search unavailable ({}); using full-text only", e.getMessage());
        }
        List<ContentItem> lexical = content.fullTextSearch(query, section, limit);
        return Rrf.fuse(ContentItem::url, limit, semantic, lexical);
    }

    private static ContentItem toItem(VectorStore.Match m) {
        String slug = m.url().startsWith("/posts/") ? m.url().substring("/posts/".length()) : null;
        String snippet = m.content().length() > 300 ? m.content().substring(0, 300) + "…" : m.content();
        return new ContentItem(m.docType(), m.docId(), slug, m.title(), snippet, m.section(), m.url(), m.source(), m.publishedAt());
    }
}
