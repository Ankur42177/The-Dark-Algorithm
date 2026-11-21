package com.pulse.assistant.embed;

import com.pulse.assistant.search.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Handles one CloudEvents-style envelope (PLAN §9.1):
 * <pre>{"id":"…","type":"content.post.published","source":"content-service","time":"…","schemaVersion":1,"data":{…}}</pre>
 * IDEMPOTENT: Kafka delivers "at least once", so the same event may arrive twice. processed_events remembers
 * event ids; a duplicate is skipped. (And re-embedding is an UPSERT anyway — doubly safe.)
 * If handling FAILS, the id is un-marked again, so Kafka's retry really retries instead of being skipped as a
 * "duplicate" (a subtle bug in the first version).
 * Kept separate from the Kafka listener so tests can call it directly.
 */
@Component
public class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    private final EmbedderService embedder;
    private final VectorStore vectors;
    private final JsonMapper json;

    public EventHandler(EmbedderService embedder, VectorStore vectors, JsonMapper json) {
        this.embedder = embedder;
        this.vectors = vectors;
        this.json = json;
    }

    /** @return false if the event was a duplicate or unknown type */
    public boolean handle(String payload) {
        JsonNode e = json.readTree(payload);                           // malformed JSON throws → retried, then DLT
        String id = text(e, "id");
        String type = text(e, "type");
        JsonNode data = e.get("data");
        if (id == null || type == null || data == null) {
            throw new IllegalArgumentException("not an event envelope");
        }
        if (!vectors.markProcessed(id)) {
            log.debug("duplicate event {} skipped", id);
            return false;
        }
        try {
            return apply(type, data);
        } catch (RuntimeException ex) {
            vectors.unmarkProcessed(id);                               // let the retry run for real
            throw ex;
        }
    }

    private boolean apply(String type, JsonNode data) {
        String postId = first(data, "postId", "id");
        String status = text(data, "status");
        switch (type) {
            case "content.post.published", "content.post.updated" -> {
                if (status != null && !"PUBLISHED".equals(status)) {
                    embedder.deletePost(postId);                       // e.g. moved back to DRAFT: not public any more
                } else {
                    // indexPost re-reads the post from content-service (the event is a NOTIFICATION; the service owns the
                    // truth). If it's no longer public, indexPost deletes its chunks.
                    embedder.indexPost(postId, text(data, "slug"));
                }
            }
            case "content.post.unpublished", "content.post.deleted" -> embedder.deletePost(postId);
            case "ingestion.article.ingested" -> {
                String url = text(data, "url");
                if (url == null || text(data, "title") == null) {
                    throw new IllegalArgumentException("article event without url/title");
                }
                String docId = text(data, "urlHash") != null ? text(data, "urlHash") : EmbedderService.articleId(url);
                embedder.indexArticle(docId, url, text(data, "title"), first(data, "excerpt", "summary"), text(data, "section"),
                        first(data, "sourceName", "source"), parse(text(data, "publishedAt")), text(data, "imageUrl"));
            }
            default -> {
                log.debug("ignoring event type {}", type);
                return false;
            }
        }
        return true;
    }

    private static String first(JsonNode n, String a, String b) {
        String v = text(n, a);
        return v != null ? v : text(n, b);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Instant parse(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
