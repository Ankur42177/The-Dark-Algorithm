package com.pulse.assistant.tools;

import com.pulse.assistant.content.ContentClient;
import com.pulse.assistant.content.ContentClient.ContentItem;
import com.pulse.assistant.content.SourceCatalog;
import com.pulse.assistant.search.HybridSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harvey's tools: plain Java methods the LLM may ask us to call (PLAN §13.2).
 *
 * <p>{@code @Tool(description=…)} is what the MODEL sees: Spring AI turns the method name, description and
 * parameters ({@code @ToolParam}) into a JSON schema sent with every request. The model never runs code — it only
 * returns "please call getLatestPosts with {section: FOOTBALL}", and OUR code decides whether and how to run it.
 * {@code resultConverter = RawTextResultConverter.class}: return our text as-is (see that class).
 *
 * <p>One instance per chat request. It counts calls (budget) and keeps a numbered list of every SOURCE it showed
 * the model: each item carries {@code "ref": n}, the model cites it as [n], and the service turns the [n]s it finds
 * in the answer into citation cards. The same numbering is used for sources fetched BEFORE the model runs
 * (pre-retrieval, see {@link #prefetch}).
 */
public class AgentTools {

    /**
     * A source card for the UI (SSE "citation" event).
     *
     * @param ref      the number the answer uses: "[2]" → ref 2
     * @param external true for articles on other sites (open in a new tab), false for Pulse posts
     */
    public record Citation(int ref, String title, String url, String slug, String source, String section, String type,
                           String imageUrl, boolean external) {
    }

    /** A navigation suggestion (SSE "action" event). {@code auto}: the reader explicitly asked to go there. */
    public record NavigateAction(String type, String path, boolean auto, String label) {
    }

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final ContentClient content;
    private final HybridSearch search;
    private final SourceCatalog sources;
    private final JsonMapper json;
    private final int maxCalls;
    private final String userMessage;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, Citation> byUrl = new LinkedHashMap<>();
    /** ref → the text the model was shown for it (title + snippet): used to check the model's [n] claims. */
    private final Map<Integer, String> evidence = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<NavigateAction> actions = new CopyOnWriteArrayList<>();
    private final List<String> toolNames = new CopyOnWriteArrayList<>();
    private volatile boolean modelUsedTools;

    public AgentTools(ContentClient content, HybridSearch search, SourceCatalog sources, JsonMapper json, int maxCalls, String userMessage) {
        this.content = content;
        this.search = search;
        this.sources = sources;
        this.json = json;
        this.maxCalls = maxCalls;
        this.userMessage = userMessage;
    }

    // ------------------------------------------------------------------ tools the MODEL may call

    @Tool(resultConverter = RawTextResultConverter.class, description = "Get the newest content, newest first: Ankur's posts "
            + "and articles from the official blogs Pulse follows. Use for anything about what is new, latest or recent, "
            + "or 'what has <publisher> published'.")
    public String getLatestPosts(
            @ToolParam(required = false, description = "Section: TECH (Code & Cortex: software + AI) or FOOTBALL. Omit for both.") String section,
            @ToolParam(required = false, description = "Only articles from this publisher, e.g. 'LangChain', 'Google DeepMind', 'Anthropic'.") String source,
            @ToolParam(required = false, description = "Only items from the last N days, e.g. 7 for 'this week'.") Integer sinceDays,
            @ToolParam(required = false, description = "How many items per group, 1 to 8. Default 4.") Integer limit) {
        if (!budget("getLatestPosts")) {
            return exhausted();
        }
        return latest(ContentClient.canonicalSection(section), source, sinceDays, clamp(limit, 4, 8), "getLatestPosts");
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "Search Ankur's posts and the curated articles by "
            + "meaning and keywords. Use for questions about a topic, a player, a team, a concept or a technology.")
    public String searchContent(
            @ToolParam(description = "What to search for, in a few words.") String query,
            @ToolParam(required = false, description = "Section: TECH or FOOTBALL. Omit to search both.") String section,
            @ToolParam(required = false, description = "How many results, 1 to 8. Default 5.") Integer limit) {
        if (!budget("searchContent")) {
            return exhausted();
        }
        if (query == null || query.isBlank()) {
            return UntrustedData.wrap("searchContent", "{\"error\":\"query is required\"}");
        }
        String q = query.length() > 200 ? query.substring(0, 200) : query;
        return render("searchContent", Map.of("items", rows(search.search(q, ContentClient.canonicalSection(section), clamp(limit, 5, 8)))));
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "Read one Pulse post by its slug (the last part of /posts/<slug>).")
    public String getPost(@ToolParam(description = "The post slug, e.g. kafka-consumer-groups-explained") String slug) {
        if (!budget("getPost")) {
            return exhausted();
        }
        if (slug == null || !slug.matches("[a-z0-9-]{1,160}")) {
            return UntrustedData.wrap("getPost", "{\"error\":\"invalid slug\"}");
        }
        return content.post(slug).map(p -> {
            String text = p.bodyHtml() == null ? "" : p.bodyHtml().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            int ref = register(new ContentItem("POST", p.id(), p.slug(), p.title(), p.summary(), p.section(), "/posts/" + p.slug(),
                    "Pulse", p.publishedAt(), p.coverImageUrl()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ref);
            m.put("title", p.title());
            m.put("url", "/posts/" + p.slug());
            m.put("section", p.section());
            m.put("publishedAt", String.valueOf(p.publishedAt()));
            m.put("summary", p.summary());
            m.put("text", text.length() > 2500 ? text.substring(0, 2500) + "…" : text);
            return UntrustedData.wrap("getPost", json.writeValueAsString(m));
        }).orElse(UntrustedData.wrap("getPost", "{\"error\":\"no published post with that slug\"}"));
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "List Pulse's sections (Code & Cortex = TECH, and Football) "
            + "with how many posts each has, plus the publishers Pulse follows.")
    public String listSections() {
        if (!budget("listSections")) {
            return exhausted();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : content.sections()) {
            String key = ContentClient.canonicalSection(n.has("section") ? n.get("section").asString() : null);
            if (key == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", key);
            m.put("label", "TECH".equals(key) ? "Code & Cortex" : "Football");
            m.put("path", "TECH".equals(key) ? "/tech" : "/football");
            m.put("posts", n.has("posts") ? n.get("posts").asLong() : 0);
            out.add(m);
        }
        return UntrustedData.wrap("listSections", json.writeValueAsString(Map.of("sections", out, "publishersFollowed", sources.names())));
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "Open a page of the Pulse website for the reader. Only use "
            + "when the user asks to go to / open / be taken to a page. Allowed paths: '/', '/tech', '/football', '/contact', "
            + "'/#newsletter', '/posts/<slug>'.")
    public String navigate(@ToolParam(description = "The internal path, e.g. /football") String path) {
        if (!budget("navigate")) {
            return exhausted();
        }
        return addNavigation(path, NavigationPolicy.userAskedToGo(userMessage));
    }

    // ------------------------------------------------------------------ used by the service (not offered to the model)

    /** What kind of context to fetch before the model runs (decided by {@code IntentRouter}). */
    public enum Prefetch { SEARCH, LATEST, SOURCES }

    /**
     * PRE-RETRIEVAL: fetch sources BEFORE asking the model, so even a small model that "forgets" to call a tool
     * answers from real content. Doesn't count against the model's tool budget; shows up as "retrieve:*" in usage.
     */
    public String prefetch(Prefetch kind, String query, String section, List<String> sourceNames) {
        toolNames.add("retrieve:" + kind.name().toLowerCase(java.util.Locale.ROOT));
        return switch (kind) {
            case SEARCH -> render("searchContent", Map.of("query", query, "items", rows(search.search(query, section, 5))));
            case LATEST -> latest(section, null, null, 4, "getLatestPosts");
            case SOURCES -> {
                List<Map<String, Object>> groups = new ArrayList<>();
                for (String s : sourceNames.stream().limit(3).toList()) {
                    groups.add(Map.of("publisher", s, "items", rows(content.latestArticles(null, s, null, 4))));
                }
                yield render("getLatestPosts", Map.of("byPublisher", groups));
            }
        };
    }

    /** Deterministic navigation (IntentRouter) or the model's navigate tool both end here. */
    public String addNavigation(String rawPath, boolean auto) {
        String path = NavigationPolicy.normalise(rawPath);
        if (!NavigationPolicy.isAllowed(path)) {
            log.warn("Blocked navigation to non-allow-listed path: {}", rawPath);
            return "{\"ok\":false,\"error\":\"That path is not allowed. Only internal Pulse pages can be opened.\"}";
        }
        if (actions.stream().noneMatch(a -> a.path().equals(path))) {
            actions.add(new NavigateAction("NAVIGATE", path, auto, NavigationPolicy.label(path)));
        }
        return "{\"ok\":true,\"path\":\"" + path + "\"}";
    }

    // ------------------------------------------------------------------ state read by the agent loop

    /** Calls the MODEL asked for (pre-retrieval not included). */
    public int callCount() {
        return calls.get();
    }

    public boolean modelUsedTools() {
        return modelUsedTools;
    }

    /** Every source shown to the model, in ref order. */
    public List<Citation> citations() {
        synchronized (byUrl) {
            return List.copyOf(byUrl.values());
        }
    }

    public List<NavigateAction> actions() {
        return List.copyOf(actions);
    }

    /** What the model saw for each source number. */
    public Map<Integer, String> evidence() {
        return Map.copyOf(evidence);
    }

    /** Tools used, in order (shown in the "done" event and used by evals). */
    public List<String> toolNames() {
        return List.copyOf(toolNames);
    }

    // ------------------------------------------------------------------ helpers

    private String latest(String section, String source, Integer sinceDays, int limit, String tool) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source != null && !source.isBlank()) {
            String exact = sources.resolve(source);
            if (exact == null) {
                out.put("note", "Unknown publisher '" + source.replaceAll("[^\\p{L}\\p{N} ._()-]", "") + "'. Publishers Pulse follows: "
                        + String.join(", ", sources.names()));
            } else {
                out.put("publisher", exact);
                out.put("articles", rows(content.latestArticles(section, exact, sinceDays, limit)));
            }
            return render(tool, out);
        }
        out.put("ankursPosts", rows(content.latestPosts(section, sinceDays, limit)));
        out.put("aroundTheWeb", rows(content.latestArticles(section, null, sinceDays, limit)));
        return render(tool, out);
    }

    private boolean budget(String name) {
        modelUsedTools = true;
        int n = calls.incrementAndGet();
        toolNames.add(name);
        log.info("tool call #{} {}", n, name);
        return n <= maxCalls;
    }

    private String exhausted() {
        return "{\"error\":\"Tool budget exhausted for this question. Answer with the information you already have.\"}";
    }

    private String render(String tool, Map<String, Object> body) {
        return UntrustedData.wrap(tool, json.writeValueAsString(body));
    }

    private List<Map<String, Object>> rows(List<ContentItem> items) {
        return items.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", register(i));
            m.put("kind", "POST".equals(i.type()) ? "Ankur's post on Pulse" : "external article");
            m.put("title", i.title());
            m.put("url", i.url());
            m.put("source", i.source());
            m.put("section", i.section());
            m.put("publishedAt", i.publishedAt() == null ? null : i.publishedAt().toString().substring(0, 10));
            m.put("text", i.summary());
            return m;
        }).toList();
    }

    /** Give a source its citation number (the same URL always keeps the same number). */
    private int register(ContentItem i) {
        if (i.url() == null || i.title() == null) {
            return 0;
        }
        synchronized (byUrl) {
            Citation existing = byUrl.get(i.url());
            if (existing != null) {
                evidence.merge(existing.ref(), i.summary() == null ? "" : i.summary(), (a, b) -> a + " " + b);
                return existing.ref();
            }
            int ref = byUrl.size() + 1;
            evidence.put(ref, i.title() + " " + (i.summary() == null ? "" : i.summary()));
            boolean external = !i.url().startsWith("/");
            byUrl.put(i.url(), new Citation(ref, i.title(), i.url(), i.slug(), i.source(), i.section(),
                    i.type() == null ? (external ? "ARTICLE" : "POST") : i.type(), i.imageUrl(), external));
            return ref;
        }
    }

    private static int clamp(Integer v, int dflt, int max) {
        return v == null ? dflt : Math.max(1, Math.min(max, v));
    }
}
