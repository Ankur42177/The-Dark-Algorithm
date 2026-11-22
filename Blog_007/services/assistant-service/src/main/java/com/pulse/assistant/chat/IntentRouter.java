package com.pulse.assistant.chat;

import com.pulse.assistant.content.SourceCatalog;
import com.pulse.assistant.tools.AgentTools.Prefetch;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny, deterministic "front desk" in front of the LLM. Real assistants do this too: cheap rules handle the
 * obvious cases instantly and reliably; the (slow, non-deterministic) model handles the rest.
 * <ol>
 *   <li><b>Navigation</b> — "take me to the football section" → answered WITHOUT the model (≈ 0 ms, always right).</li>
 *   <li><b>Small talk</b> — "hi", "thanks", "who are you" → the model answers from its persona, no search.</li>
 *   <li><b>Retrieval plan</b> — everything else gets context fetched BEFORE the model runs:
 *       publisher named ("What's new from LangChain?") → that publisher's newest articles;
 *       "new / latest / recent" → newest posts + articles; otherwise → hybrid search for the question.</li>
 * </ol>
 * Java note: a {@code sealed interface} with {@code record}s is a closed set of result types — the compiler knows
 * every possible Route, so a {@code switch} over them needs no "default".
 */
@Component
public class IntentRouter {

    public sealed interface Route permits Navigate, SmallTalk, Answer {
    }

    /** Go straight to {@code path}; {@code reply} is what Harvey says. */
    public record Navigate(String path, String reply) implements Route {
    }

    public record SmallTalk() implements Route {
    }

    /** Ask the model, after fetching {@code prefetch} context. */
    public record Answer(Prefetch prefetch, String section, List<String> sources) implements Route {
    }

    // "take me to the football section", "go to code & cortex", "open the newsletter", "bring me home please"
    private static final Pattern NAV = Pattern.compile(
            "^(?:please\\s+|can you\\s+|could you\\s+|harvey,?\\s+)*(?:take me|bring me|send me|go|navigate|head|jump|open|show me)"
                    + "(?:\\s+(?:back|over))?(?:\\s+to)?(?:\\s+(?:the|my|your))?\\s+"
                    + "(football|soccer|tech|code (?:&|and) cortex|cortex|ai|home|homepage|home page|front page|start|contact|contact page|newsletter)"
                    + "(?:\\s+(?:section|page|arena|area|tab|sign ?up|form))?(?:\\s+please)?[.!?]*$");

    private static final Pattern SMALL_TALK = Pattern.compile(
            "^(?:hi|hey|hello|hiya|yo|sup|good (?:morning|afternoon|evening)|thanks?(?: you)?|thank you(?: so much)?|cheers|ok(?:ay)?|cool|great|nice|bye|"
                    + "who are you|what are you|what(?:'s| is) your name|what can you do|how are you|help)(?:\\s+harvey)?[.!?]*$");

    private static final Pattern LATEST = Pattern.compile(
            "\\b(new|newest|latest|recent|recently|lately|this week|today|yesterday|this month|just published|updates?|what's up with"
                    + "|hot|trending|popular|right now|read next)\\b");

    private final SourceCatalog sources;

    public IntentRouter(SourceCatalog sources) {
        this.sources = sources;
    }

    public Route route(String message) {
        String m = message.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").trim();
        Matcher nav = NAV.matcher(m);
        if (nav.matches()) {
            String path = switch (nav.group(1)) {
                case "football", "soccer" -> "/football";
                case "tech", "code & cortex", "code and cortex", "cortex", "ai" -> "/tech";
                case "contact", "contact page" -> "/contact";
                case "newsletter" -> "/#newsletter";
                default -> "/";
            };
            return new Navigate(path, navReply(path));
        }
        if (SMALL_TALK.matcher(m).matches()) {
            return new SmallTalk();
        }
        String section = m.matches(".*\\b(football|soccer|premier league|champions league)\\b.*") ? "FOOTBALL"
                : m.matches(".*(\\bcode (&|and) cortex\\b|\\btech\\b|\\bai\\b|\\bengineering\\b).*") ? "TECH" : null;
        List<String> named = sources.mentionedIn(m);
        if (!named.isEmpty()) {
            return new Answer(LATEST.matcher(m).find() || m.matches(".*\\b(from|by|on|at)\\b.*") ? Prefetch.SOURCES : Prefetch.SEARCH,
                    null, named);
        }
        if (LATEST.matcher(m).find() && m.split(" ").length <= 12) {
            return new Answer(Prefetch.LATEST, section, List.of());
        }
        return new Answer(Prefetch.SEARCH, null, List.of());
    }

    static String navReply(String path) {
        return switch (path) {
            case "/football" -> "Heading to Football: tactics, legends and the big European nights.";
            case "/tech" -> "Heading to Code & Cortex: systems that scale and the AI on top of them.";
            case "/contact" -> "Opening the contact page. Ankur reads every message.";
            case "/#newsletter" -> "Taking you to the newsletter sign-up: one email a month, no spam.";
            default -> "Taking you back to the home page.";
        };
    }
}
