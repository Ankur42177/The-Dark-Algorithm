package com.pulse.assistant.tools;

import java.util.regex.Pattern;

/**
 * The ONLY places the agent may send a reader (PLAN §13.2). An LLM can be tricked (prompt injection) into
 * "navigating" to evil.example — this allow-list makes that impossible: the code decides, not the model.
 */
public final class NavigationPolicy {

    private static final Pattern ALLOWED = Pattern.compile(
            "^/$|^/(tech|football|ai)$|^/posts/[a-z0-9-]{1,160}$|^/search\\?q=[A-Za-z0-9%+ _-]{1,100}$");

    private NavigationPolicy() {
    }

    public static boolean isAllowed(String path) {
        return path != null && ALLOWED.matcher(path).matches();
    }

    /** "Take me to the football section" → navigate automatically; otherwise only show a button. */
    public static boolean userAskedToGo(String userMessage) {
        return userMessage != null && userMessage.toLowerCase().matches(
                ".*\\b(take me|go to|navigate|open|bring me|show me the)\\b.*");
    }
}
