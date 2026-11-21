package com.pulse.assistant.tools;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The ONLY places Harvey may send a reader (PLAN §13.2). An LLM can be tricked (prompt injection) into
 * "navigating" to evil.example — this allow-list makes that impossible: the code decides, not the model.
 *
 * <p>Site map (web/): {@code /} home (newsletter block at {@code /#newsletter}), {@code /tech} ("Code & Cortex"),
 * {@code /football}, {@code /contact}, {@code /posts/<slug>}. The old {@code /ai} section redirects to /tech, so we
 * normalise it here instead of sending readers through a redirect.
 */
public final class NavigationPolicy {

    private static final Pattern ALLOWED = Pattern.compile(
            "^/$|^/(tech|football|contact)$|^/#newsletter$|^/posts/[a-z0-9-]{1,160}$");

    /** Human names for the "Take me to … →" button. */
    private static final Map<String, String> LABELS = Map.of(
            "/", "the home page", "/tech", "Code & Cortex", "/football", "Football",
            "/contact", "the contact page", "/#newsletter", "the newsletter");

    private NavigationPolicy() {
    }

    /** Tidy what a model might send: "football" → "/football", "/ai" → "/tech", "/tech/" → "/tech". */
    public static String normalise(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        if (!p.startsWith("/") && p.matches("[a-z-]+")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return switch (p.toLowerCase(Locale.ROOT)) {
            case "/ai", "/code-and-cortex" -> "/tech";
            case "/home", "/index" -> "/";
            case "/newsletter" -> "/#newsletter";
            default -> p;
        };
    }

    public static boolean isAllowed(String path) {
        return path != null && ALLOWED.matcher(path).matches();
    }

    public static String label(String path) {
        String l = LABELS.get(path);
        return l != null ? l : path != null && path.startsWith("/posts/") ? "the post" : path;
    }

    /** "Take me to the football section" → navigate automatically; otherwise only show a button. */
    public static boolean userAskedToGo(String userMessage) {
        return userMessage != null && userMessage.toLowerCase(Locale.ROOT).matches(
                "(?s).*\\b(take me|go to|navigate|open|bring me|show me the|head to|jump to|send me)\\b.*");
    }
}
