package com.pulse.assistant.tools;

import com.pulse.assistant.content.ContentClient;
import com.pulse.assistant.content.ContentClient.ContentItem;
import com.pulse.assistant.search.HybridSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The agent's tools: plain Java methods the LLM may ask us to call (PLAN §13.2).
 *
 * <p>{@code @Tool(resultConverter = RawTextResultConverter.class, description=…)} is what the MODEL sees: Spring AI turns the method name, description and
 * parameters into a JSON schema sent with every request. The model never runs code — it only returns
 * "please call getLatestPosts with {section: AI}", and OUR code decides whether and how to run it.
 *
 * <p>One instance per chat request: it counts calls (budget), and remembers what it returned so the
 * service can emit citation and navigation events.
 */
public class AgentTools {

    public record Citation(String title, String url, String slug, String source) {
    }

    public record NavigateAction(String type, String path, boolean auto) {
    }

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final ContentClient content;
    private final HybridSearch search;
    private final JsonMapper json;
    private final int maxCalls;
    private final String userMessage;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, Citation> seen = new LinkedHashMap<>();
    private final List<NavigateAction> actions = new ArrayList<>();
    private final List<String> toolNames = new java.util.concurrent.CopyOnWriteArrayList<>();

    public AgentTools(ContentClient content, HybridSearch search, JsonMapper json, int maxCalls, String userMessage) {
        this.content = content;
        this.search = search;
        this.json = json;
        this.maxCalls = maxCalls;
        this.userMessage = userMessage;
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "Get the most recent posts and articles on Pulse, newest first. "
            + "Use for any question about what is new, latest or recent.")
    public String getLatestPosts(
            @ToolParam(required = false, description = "Section: TECH, FOOTBALL or AI. Omit for all sections.") String section,
            @ToolParam(required = false, description = "Only items from the last N days, e.g. 7 for 'this week'.") Integer sinceDays,
            @ToolParam(required = false, description = "How many items, 1 to 10. Default 5.") Integer limit) {
        if (!budget("getLatestPosts")) {
            return exhausted();
        }
        List<ContentItem> items = content.latest(normalizeSection(section), sinceDays, clamp(limit, 5, 10));
        return result("getLatestPosts", items);
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "Search Pulse posts and curated articles by meaning and keywords. "
            + "Use for questions about a topic, e.g. 'Kafka', 'expected goals', 'RAG'.")
    public String searchContent(
            @ToolParam(description = "What to search for, in a few words.") String query,
            @ToolParam(required = false, description = "Section: TECH, FOOTBALL or AI.") String section,
            @ToolParam(required = false, description = "How many results, 1 to 8. Default 5.") Integer limit) {
        if (!budget("searchContent")) {
            return exhausted();
        }
        if (query == null || query.isBlank()) {
            return UntrustedData.wrap("searchContent", "{\"error\":\"query is required\"}");
        }
        String q = query.length() > 200 ? query.substring(0, 200) : query;
        return result("searchContent", search.search(q, normalizeSection(section), clamp(limit, 5, 8)));
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "Get one Pulse post by its slug (the last part of /posts/<slug>).")
    public String getPost(@ToolParam(description = "The post slug, e.g. kafka-consumer-groups-explained") String slug) {
        if (!budget("getPost")) {
            return exhausted();
        }
        if (slug == null || !slug.matches("[a-z0-9-]{1,160}")) {
            return UntrustedData.wrap("getPost", "{\"error\":\"invalid slug\"}");
        }
        return content.post(slug).map(p -> {
            String text = p.bodyHtml() == null ? "" : p.bodyHtml().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            remember(new Citation(p.title(), "/posts/" + p.slug(), p.slug(), "Pulse"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", p.title());
            m.put("url", "/posts/" + p.slug());
            m.put("section", p.section());
            m.put("publishedAt", String.valueOf(p.publishedAt()));
            m.put("summary", p.summary());
            m.put("text", text.length() > 1500 ? text.substring(0, 1500) + "…" : text);
            return UntrustedData.wrap("getPost", json.writeValueAsString(m));
        }).orElse(UntrustedData.wrap("getPost", "{\"error\":\"no published post with that slug\"}"));
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "List the sections of Pulse (Tech, Football, AI) with how many posts each has.")
    public String listSections() {
        if (!budget("listSections")) {
            return exhausted();
        }
        return UntrustedData.wrap("listSections", json.writeValueAsString(content.sections()));
    }

    @Tool(resultConverter = RawTextResultConverter.class, description = "Navigate the reader to a page of the Pulse website. Only use when the user asks to go "
            + "somewhere. Allowed paths: '/', '/tech', '/football', '/ai', '/posts/<slug>', '/search?q=<words>'.")
    public String navigate(@ToolParam(description = "The internal path, e.g. /football") String path) {
        if (!budget("navigate")) {
            return exhausted();
        }
        if (!NavigationPolicy.isAllowed(path)) {
            log.warn("Blocked navigation to non-allow-listed path: {}", path);
            return "{\"ok\":false,\"error\":\"That path is not allowed. Only internal Pulse pages can be opened.\"}";
        }
        actions.add(new NavigateAction("NAVIGATE", path, NavigationPolicy.userAskedToGo(userMessage)));
        return "{\"ok\":true,\"path\":\"" + path + "\"}";
    }

    // ------------------------------------------------------------------ state read by the agent loop

    public int callCount() {
        return calls.get();
    }

    public List<Citation> citations() {
        return List.copyOf(seen.values());
    }

    public List<NavigateAction> actions() {
        return List.copyOf(actions);
    }

    /** Names of the tools the model asked for, in order (shown in the "done" event and used by evals). */
    public List<String> toolNames() {
        return List.copyOf(toolNames);
    }

    // ------------------------------------------------------------------ helpers

    private boolean budget(String name) {
        int n = calls.incrementAndGet();
        toolNames.add(name);
        log.info("tool call #{} {}", n, name);
        return n <= maxCalls;
    }

    private String exhausted() {
        return "{\"error\":\"Tool budget exhausted for this question. Answer with the information you already have.\"}";
    }

    private String result(String tool, List<ContentItem> items) {
        items.forEach(i -> remember(new Citation(i.title(), i.url(), i.slug(), i.source())));
        List<Map<String, Object>> rows = items.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", i.type());
            m.put("title", i.title());
            m.put("url", i.url());
            m.put("source", i.source());
            m.put("section", i.section());
            m.put("publishedAt", String.valueOf(i.publishedAt()));
            m.put("summary", i.summary());
            return m;
        }).toList();
        return UntrustedData.wrap(tool, json.writeValueAsString(Map.of("count", rows.size(), "items", rows)));
    }

    private void remember(Citation c) {
        if (c.url() != null && c.title() != null) {
            seen.putIfAbsent(c.url(), c);
        }
    }

    private static String normalizeSection(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String u = s.trim().toUpperCase();
        return switch (u) {
            case "TECH", "FOOTBALL", "AI" -> u;
            default -> null;
        };
    }

    private static int clamp(Integer v, int dflt, int max) {
        return v == null ? dflt : Math.max(1, Math.min(max, v));
    }
}
