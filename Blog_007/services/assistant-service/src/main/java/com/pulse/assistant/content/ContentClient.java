package com.pulse.assistant.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Talks to content-service over HTTP. The assistant NEVER reads content_db directly: each service owns its
 * data (database-per-service). Every method degrades gracefully: an endpoint that doesn't exist yet (404) or
 * a failure returns an empty result instead of breaking the chat.
 *
 * <p>JSON is read loosely ({@link JsonNode}) so small changes in content-service's responses (new fields,
 * array vs. page object) don't break the assistant — the "tolerant reader" pattern.
 */
@Component
public class ContentClient {

    /** A normalised piece of content the agent can cite: one of Ankur's posts or an external article. */
    public record ContentItem(String type, String id, String slug, String title, String summary, String section,
                              String url, String source, Instant publishedAt) {
    }

    public record PostBody(String id, String slug, String title, String summary, String section, String bodyHtml,
                           Instant publishedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(ContentClient.class);

    private final RestClient http;
    private final JsonMapper json;

    public ContentClient(RestClient contentRestClient, JsonMapper json) {
        this.http = contentRestClient;
        this.json = json;
    }

    /** Latest posts (and, when Phase 7 is live, external articles) in a section, newest first. */
    public List<ContentItem> latest(String section, Integer sinceDays, int limit) {
        String sec = section == null ? "" : "&section=" + section;
        Instant since = sinceDays == null ? null : Instant.now().minus(sinceDays, ChronoUnit.DAYS);
        List<ContentItem> out = new ArrayList<>();
        // Prefer the internal API (Phase 8 addition in content-service); fall back to the public list.
        JsonNode posts = get("/internal/v1/posts?limit=" + limit + sec + (since == null ? "" : "&since=" + since))
                .or(() -> get("/api/v1/posts?limit=" + limit + sec)).orElse(null);
        items(posts).forEach(n -> out.add(toPost(n)));
        get("/api/v1/feed?limit=" + limit + sec).ifPresent(feed -> items(feed).forEach(n -> out.add(toArticle(n))));
        return out.stream()
                .filter(i -> since == null || i.publishedAt() == null || !i.publishedAt().isBefore(since))
                .sorted((a, b) -> compareDesc(a.publishedAt(), b.publishedAt()))
                .limit(limit)
                .toList();
    }

    /** Full-text search in content-service (Postgres tsvector). Empty if the endpoint isn't available. */
    public List<ContentItem> fullTextSearch(String query, String section, int limit) {
        String q = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        String sec = section == null ? "" : "&section=" + section;
        return get("/api/v1/search?q=" + q + "&limit=" + limit + sec)
                .map(node -> items(node).stream().map(n -> n.has("url") && !n.has("slug") ? toArticle(n) : toPost(n)).toList())
                .orElse(List.of());
    }

    public Optional<PostBody> post(String slug) {
        return get("/api/v1/posts/" + slug).map(n -> new PostBody(text(n, "id"), text(n, "slug"), text(n, "title"),
                text(n, "summary"), text(n, "section"), text(n, "bodyHtml"), instant(n, "publishedAt")));
    }

    public List<JsonNode> sections() {
        return get("/api/v1/sections").map(this::items).orElse(List.of());
    }

    /** Every published post (for the embedding backfill): walks the keyset cursor until the end. */
    public List<ContentItem> allPublishedPosts() {
        List<ContentItem> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 200; page++) {
            String path = "/api/v1/posts?limit=50" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode node = get(path).orElse(null);
            if (node == null) {
                break;
            }
            items(node).forEach(n -> all.add(toPost(n)));
            cursor = node.has("nextCursor") && !node.get("nextCursor").isNull() ? node.get("nextCursor").asString() : null;
            if (cursor == null) {
                break;
            }
        }
        return all;
    }

    // ------------------------------------------------------------------ helpers

    private Optional<JsonNode> get(String path) {
        try {
            String body = http.get().uri(path).retrieve().body(String.class);
            return body == null || body.isBlank() ? Optional.empty() : Optional.of(json.readTree(body));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();                         // endpoint (or post) doesn't exist: fine
        } catch (RuntimeException e) {
            log.warn("content-service call {} failed: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private List<JsonNode> items(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        JsonNode arr = node.isArray() ? node : node.get("items");
        List<JsonNode> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    private ContentItem toPost(JsonNode n) {
        String slug = text(n, "slug");
        return new ContentItem("POST", text(n, "id"), slug, text(n, "title"), text(n, "summary"), text(n, "section"),
                "/posts/" + slug, "Pulse", instant(n, "publishedAt"));
    }

    private ContentItem toArticle(JsonNode n) {
        String summary = text(n, "aiSummary") != null ? text(n, "aiSummary") : text(n, "excerpt");
        return new ContentItem("ARTICLE", text(n, "id"), null, text(n, "title"), summary, text(n, "section"),
                text(n, "url"), text(n, "sourceName") == null ? "web" : text(n, "sourceName"), instant(n, "publishedAt"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        try {
            return s == null ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int compareDesc(Instant a, Instant b) {
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return b.compareTo(a);
    }
}
