package com.pulse.assistant.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 *
 * <p>Sections: Pulse has TWO arenas, TECH ("Code & Cortex": engineering + AI) and FOOTBALL. The old AI
 * section was merged into TECH; {@link #canonicalSection(String)} maps any leftover "AI" to "TECH".
 */
@Component
public class ContentClient {

    /** A normalised piece of content the agent can cite: one of Ankur's posts or an external article. */
    public record ContentItem(String type, String id, String slug, String title, String summary, String section,
                              String url, String source, Instant publishedAt, String imageUrl) {
    }

    public record PostBody(String id, String slug, String title, String summary, String section, String bodyHtml,
                           Instant publishedAt, String coverImageUrl, List<String> tags) {
    }

    /** How far back "latest" looks when the caller doesn't say (10 years = "everything"). */
    static final int ALL_TIME_DAYS = 3650;

    private static final Logger log = LoggerFactory.getLogger(ContentClient.class);

    private final RestClient http;
    private final JsonMapper json;
    private final String baseUrl;

    public ContentClient(RestClient contentRestClient, JsonMapper json, com.pulse.assistant.config.AssistantProperties props) {
        this.http = contentRestClient;
        this.json = json;
        String u = props.contentServiceUrl();
        this.baseUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** "AI" (legacy) → "TECH"; unknown values → null (= all sections). */
    public static String canonicalSection(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String u = s.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (u) {
            case "TECH", "AI", "CODE & CORTEX", "CODE AND CORTEX", "CODE_AND_CORTEX" -> "TECH";
            case "FOOTBALL", "SOCCER" -> "FOOTBALL";
            default -> null;
        };
    }

    /** Ankur's newest published posts (optionally one section, optionally only the last N days). */
    public List<ContentItem> latestPosts(String section, Integer sinceDays, int limit) {
        String sec = section == null ? "" : "&section=" + section;
        int days = sinceDays == null ? ALL_TIME_DAYS : Math.max(1, Math.min(ALL_TIME_DAYS, sinceDays));
        // Internal API (service-to-service); fall back to the public list if it isn't there.
        JsonNode posts = get("/internal/v1/posts?limit=" + limit + sec + "&sinceDays=" + days)
                .or(() -> get("/api/v1/posts?limit=" + limit + sec)).orElse(null);
        Instant since = sinceDays == null ? null : Instant.now().minusSeconds(86_400L * days);
        return items(posts).stream().map(this::toPost)
                .filter(i -> since == null || i.publishedAt() == null || !i.publishedAt().isBefore(since))
                .limit(limit).toList();
    }

    /** Newest external articles ("Around the web"), optionally one section and/or one exact source name. */
    public List<ContentItem> latestArticles(String section, String source, Integer sinceDays, int limit) {
        String sec = section == null ? "" : "&section=" + section;
        String src = source == null ? "" : "&source=" + enc(source);
        Instant since = sinceDays == null ? null : Instant.now().minusSeconds(86_400L * sinceDays);
        return get("/api/v1/feed?limit=" + limit + sec + src)
                .map(feed -> items(feed).stream().map(this::toArticle)
                        .filter(i -> since == null || i.publishedAt() == null || !i.publishedAt().isBefore(since)).toList())
                .orElse(List.of());
    }

    /** Full-text search in content-service (Postgres tsvector, posts only). Empty if the endpoint isn't available. */
    public List<ContentItem> fullTextSearch(String query, String section, int limit) {
        String sec = section == null ? "" : "&section=" + section;
        return get("/api/v1/search?q=" + enc(query) + "&limit=" + limit + sec)
                .map(node -> items(node).stream().map(n -> n.has("url") && !n.has("slug") ? toArticle(n) : toPost(n)).toList())
                .orElse(List.of());
    }

    public Optional<PostBody> post(String slug) {
        return get("/api/v1/posts/" + slug).map(n -> {
            List<String> tags = new ArrayList<>();
            if (n.get("tags") != null && n.get("tags").isArray()) {
                n.get("tags").forEach(t -> tags.add(t.asString()));
            }
            return new PostBody(text(n, "id"), text(n, "slug"), text(n, "title"), text(n, "summary"),
                    canonicalSection(text(n, "section")), text(n, "bodyHtml"), instant(n, "publishedAt"), text(n, "coverImageUrl"), tags);
        });
    }

    public List<JsonNode> sections() {
        return get("/api/v1/sections").map(this::items).orElse(List.of());
    }

    /** Every published post (for the reconciler): walks the keyset cursor until the end. */
    public Optional<List<ContentItem>> allPublishedPosts() {
        return walk("/api/v1/posts?limit=50", this::toPost);
    }

    /** Every item of the external feed (for the reconciler). */
    public Optional<List<ContentItem>> allFeedItems() {
        return walk("/api/v1/feed?limit=50", this::toArticle);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Follow nextCursor page by page. Returns EMPTY (not an empty list!) if any page fails: the reconciler must
     * never conclude "the site has no posts, delete everything" just because content-service hiccupped.
     */
    private Optional<List<ContentItem>> walk(String firstPath, java.util.function.Function<JsonNode, ContentItem> mapper) {
        List<ContentItem> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            JsonNode node = get(firstPath + (cursor == null ? "" : "&cursor=" + enc(cursor))).orElse(null);
            if (node == null) {
                return Optional.empty();
            }
            items(node).forEach(n -> all.add(mapper.apply(n)));
            cursor = node.has("nextCursor") && !node.get("nextCursor").isNull() ? node.get("nextCursor").asString() : null;
            if (cursor == null) {
                return Optional.of(all);
            }
        }
        return Optional.of(all);
    }

    private Optional<JsonNode> get(String path) {
        try {
            // uri(URI) = "use exactly this, already encoded". uri(String) would treat the path as a TEMPLATE and encode it
            // AGAIN: "Claude%20(Anthropic)" → "%2520…" and the source filter silently matched nothing.
            String body = http.get().uri(java.net.URI.create(baseUrl + path)).retrieve().body(String.class);
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
        return new ContentItem("POST", text(n, "id"), slug, text(n, "title"), text(n, "summary"), canonicalSection(text(n, "section")),
                "/posts/" + slug, "Pulse", instant(n, "publishedAt"), text(n, "coverImageUrl"));
    }

    private ContentItem toArticle(JsonNode n) {
        String summary = text(n, "aiSummary") != null ? text(n, "aiSummary") : text(n, "excerpt");
        return new ContentItem("ARTICLE", text(n, "id"), null, text(n, "title"), summary, canonicalSection(text(n, "section")),
                text(n, "url"), text(n, "sourceName") == null ? "web" : text(n, "sourceName"), instant(n, "publishedAt"), text(n, "imageUrl"));
    }

    /** Query-parameter encoding: spaces as %20 (not "+"), so it means the same to every server. */
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
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
}
