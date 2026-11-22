package com.pulse.assistant.content;

import com.pulse.assistant.search.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Knows the names of the external publishers Pulse follows ("LangChain Blog", "Google DeepMind",
 * "Claude (Anthropic)", …) and maps what a READER types ("langchain", "deepmind", "anthropic") to the exact
 * name content-service filters on. Names come from the vector index (refreshed every 10 minutes), so a new
 * feed source is picked up automatically — no code change.
 *
 * <p>Why bother? A 4-billion-parameter model will happily call getLatestPosts(source="Langchain"), and an
 * exact-match filter would return nothing. Deterministic fuzzy matching in code is cheap and reliable.
 */
@Component
public class SourceCatalog {

    /** Words too generic to identify a publisher on their own. */
    private static final Set<String> GENERIC = Set.of("blog", "techblog", "the", "research", "ai", "google", "news", "and", "of");

    private static final Logger log = LoggerFactory.getLogger(SourceCatalog.class);

    private final VectorStore vectors;
    private volatile List<String> cached = List.of();
    private volatile long loadedAt;

    public SourceCatalog(VectorStore vectors) {
        this.vectors = vectors;
    }

    public List<String> names() {
        if (System.currentTimeMillis() - loadedAt > 600_000 || cached.isEmpty() && System.currentTimeMillis() - loadedAt > 30_000) {
            try {
                cached = List.copyOf(vectors.articleSources());
            } catch (RuntimeException e) {
                log.warn("could not load source names: {}", e.getMessage());
            }
            loadedAt = System.currentTimeMillis();
        }
        return cached;
    }

    /** The exact source name for what the reader/model typed, or null if nothing matches. */
    public String resolve(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String t = norm(typed);
        for (String name : names()) {
            if (norm(name).equals(t)) {
                return name;
            }
        }
        for (String name : names()) {
            if (aliases(name).stream().anyMatch(a -> a.equals(t) || t.contains(a) || a.contains(t) && t.length() >= 5)) {
                return name;
            }
        }
        return null;
    }

    /** Every source the text mentions, in catalogue order ("What's new from LangChain or DeepMind?" → 2). */
    public List<String> mentionedIn(String text) {
        String t = " " + norm(text) + " ";
        List<String> out = new ArrayList<>();
        for (String name : names()) {
            if (aliases(name).stream().anyMatch(a -> t.contains(" " + a + " "))) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * "Claude (Anthropic)" → {claude anthropic, claude, anthropic}; "Google DeepMind" → {google deepmind, deepmind};
     * "Hugging Face Blog" → {hugging face, huggingface}; "Google Research" → {google research}.
     */
    static Set<String> aliases(String name) {
        Set<String> out = new LinkedHashSet<>();
        String n = norm(name);
        String core = n.replaceAll("\\b(blog|techblog)\\b", " ").replaceAll("\\s+", " ").trim();
        out.add(n);
        if (!core.isEmpty()) {
            out.add(core);
            out.add(core.replace(" ", ""));
        }
        int open = name.indexOf('(');
        if (open > 0 && name.indexOf(')', open) > open) {
            out.add(norm(name.substring(0, open)));
            out.add(norm(name.substring(open + 1, name.indexOf(')', open))));
        }
        String[] words = core.split(" ");
        for (String w : words) {
            if (w.length() >= 5 && !GENERIC.contains(w) && words.length > 1) {
                out.add(w);                              // "deepmind", "langchain", "netflix", "cloudflare", "wilson"
            }
        }
        out.removeIf(a -> a.isBlank() || GENERIC.contains(a));
        return out;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
