package com.pulse.assistant.chat;

import com.pulse.assistant.chat.ChatEvents.Sink;
import com.pulse.assistant.chat.ChatEvents.Usage;
import com.pulse.assistant.config.AssistantProperties;
import com.pulse.assistant.content.ContentClient;
import com.pulse.assistant.content.ContentClient.ContentItem;
import com.pulse.assistant.content.SourceCatalog;
import com.pulse.assistant.search.HybridSearch;
import com.pulse.assistant.tools.AgentTools;
import com.pulse.assistant.tools.AgentTools.Citation;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Harvey's brain: the AGENT LOOP (PLAN §13.1), with a deterministic front desk and RAG-first retrieval.
 * <pre>
 *  route the message (IntentRouter):
 *     "take me to football" ──► navigation action, no model at all (instant, always right)
 *     anything else ──► fetch sources first (pre-retrieval), then:
 *  messages = [Harvey's rules, …history, question + sources]
 *  loop:
 *     stream the model's reply  ──► text tokens go straight to the browser (fast first word)
 *     did it ask for tools?  no ──► done: cite the [n] sources it used
 *                            yes ─► run them (max 4 per question), append results as UNTRUSTED data, loop
 * </pre>
 * We run the loop ourselves (instead of letting the framework do it) so every guardrail is explicit:
 * tool budget, overall deadline, circuit breaker, and a "search mode" fallback when the model is down.
 *
 * <p>Why fetch sources BEFORE the model? A 4-billion-parameter local model often "forgets" to call a tool and
 * answers "I don't have information about that" — even when the answer is in the index. Retrieval-first makes
 * grounding the default; tools stay available for follow-ups (latest posts, reading a whole post, navigation).
 */
@Service
public class AgentService {

    /** Kept for tests and docs: the fixed part of Harvey's system prompt. */
    static final String SYSTEM_PROMPT = HarveyPersona.CORE;

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** "[2]", "[1][3]", "[1, 2]" → citation markers. */
    private static final Pattern REF = Pattern.compile("(?i)\\[(?:ref\\s*:?\\s*)?(\\d{1,2}(?:\\s*,\\s*(?:ref\\s*:?\\s*)?\\d{1,2})*)\\]");
    /** Answers that say "not found / can't help" get no fallback citations and are never cached. */
    private static final Pattern REFUSAL = Pattern.compile("(?is).*(\\bi (?:don't|do not|couldn't|could not|can't|cannot|won't|will not) "
            + "(?:find|see|have|help|write|print|share|reveal|show|do|provide|answer)|\\bi(?:'m| am) (?:not able|unable) to"
            + "|\\bnot (?:covered|something (?:i|pulse))|\\boutside (?:of )?(?:what|my|pulse)|\\bno (?:posts?|articles?|sources?|information) (?:about|on)"
            + "|\\bisn't something|\\bnothing (?:on|about) (?:pulse|that)|\\bonly help with).*");
    /** "I'll search for that…" — a model's chatter before a tool call, held back and dropped. */
    private static final Pattern PREAMBLE = Pattern.compile("(?is)^\\s*(?:sure[,!.]?\\s*|okay[,!.]?\\s*|great question[,!.]?\\s*)?"
            + "(?:i'll|i will|let me|i'm going to|i am going to|first,? i'll)\\b.*");

    private final ChatModel chatModel;
    private final CircuitBreaker breaker;
    private final ContentClient content;
    private final HybridSearch search;
    private final SourceCatalog sources;
    private final IntentRouter router;
    private final ChatMemoryStore memory;
    private final AnswerCache answerCache;
    private final AssistantProperties props;
    private final JsonMapper json;

    public AgentService(ChatModel chatModel, CircuitBreaker llmCircuitBreaker, ContentClient content, HybridSearch search,
                        SourceCatalog sources, IntentRouter router, ChatMemoryStore memory, AnswerCache answerCache,
                        AssistantProperties props, JsonMapper json) {
        this.chatModel = chatModel;
        this.breaker = llmCircuitBreaker;
        this.content = content;
        this.search = search;
        this.sources = sources;
        this.router = router;
        this.memory = memory;
        this.answerCache = answerCache;
        this.props = props;
        this.json = json;
    }

    /** Answer one user message, streaming events into {@code sink}. Never throws: failures become events. */
    public void answer(String sessionId, String userMessage, Sink sink) {
        Clock clock = new Clock();
        AgentTools tools = new AgentTools(content, search, sources, json, props.maxToolCalls(), userMessage);
        IntentRouter.Route route = router.route(userMessage);

        // 0. The front desk: plain navigation needs no model.
        if (route instanceof IntentRouter.Navigate nav) {
            tools.addNavigation(nav.path(), true);
            streamText(nav.reply(), sink, clock);
            tools.actions().forEach(sink::action);
            sink.done(new Usage("nav", 0, List.of("router:navigate"), null, null, clock.ttft(), clock.total(), sessionId, null));
            memory.append(sessionId, userMessage, nav.reply());
            return;
        }

        List<ChatMemoryStore.Turn> history = memory.load(sessionId);

        // 1. Answer cache — only for the first question of a conversation (follow-ups depend on history).
        if (history.isEmpty()) {
            var cached = answerCache.get(userMessage);
            if (cached.isPresent()) {
                streamText(cached.get().answer(), sink, clock);
                cached.get().citations().forEach(sink::citation);
                cached.get().actions().forEach(sink::action);
                sink.done(new Usage("cache", 0, List.of(), null, null, clock.ttft(), clock.total(), sessionId, null));
                return;
            }
        }

        // 2. Pre-retrieval (RAG-first): sources are numbered, the model cites them as [n].
        String context = null;
        if (props.preRetrievalEnabled() && route instanceof IntentRouter.Answer plan) {
            sink.status("searching", plan.prefetch() == AgentTools.Prefetch.SEARCH ? "Searching Pulse…" : "Checking the latest…");
            try {
                context = tools.prefetch(plan.prefetch(), userMessage, plan.section(), plan.sources());
            } catch (RuntimeException e) {
                log.warn("pre-retrieval failed ({}); the model will have to use tools", e.toString());
            }
        }

        ToolCallback[] callbacks = ToolCallbacks.from(tools);          // @Tool methods → JSON-schema tool definitions
        Map<String, ToolCallback> byName = Arrays.stream(callbacks)
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(HarveyPersona.systemPrompt(sources.names())));
        for (ChatMemoryStore.Turn t : history) {
            messages.add("user".equals(t.role()) ? new UserMessage(t.text()) : new AssistantMessage(t.text()));
        }
        messages.add(new UserMessage(context == null ? userMessage : HarveyPersona.withContext(userMessage, context)));

        StringBuilder answer = new StringBuilder();
        Integer promptTokens = null;
        Integer completionTokens = null;
        boolean cutShort = false;
        try {
            sink.status("thinking", "Thinking…");
            for (int round = 0; ; round++) {
                // After the budget is spent, offer NO tools: the model must answer with what it has.
                boolean offerTools = round < props.maxToolCalls() && tools.callCount() < props.maxToolCalls();
                Round r = streamRound(new Prompt(messages, options(offerTools ? callbacks : new ToolCallback[0])), sink, clock, answer, offerTools);
                promptTokens = add(promptTokens, r.promptTokens);
                completionTokens = add(completionTokens, r.completionTokens);
                cutShort = r.cutShort;
                if (r.toolCalls.isEmpty() || sink.isClosed() || r.cutShort) {
                    break;
                }
                messages.add(AssistantMessage.builder().content(r.text).toolCalls(r.toolCalls).build());
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                for (AssistantMessage.ToolCall call : r.toolCalls) {
                    log.info("model requested tool {}({})", call.name(), abbreviate(call.arguments()));
                    sink.status("tool", toolHint(call.name()));
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), runTool(byName, call)));
                }
                messages.add(ToolResponseMessage.builder().responses(responses).build());
            }
        } catch (CallNotPermittedException e) {
            searchMode(userMessage, sink, clock, tools, sessionId, answer.length() > 0, "circuit breaker open");
            return;
        } catch (RuntimeException e) {
            log.warn("LLM failed ({}): falling back to search mode", e.toString());
            searchMode(userMessage, sink, clock, tools, sessionId, answer.length() > 0, reason(e));
            return;
        }
        if (cutShort && answer.length() > 0) {
            streamText(" …", sink, clock);
            answer.append(" …");
        }
        if (answer.toString().isBlank() && !sink.isClosed()) {
            // The model produced nothing usable (it happens with small models): degrade instead of showing silence.
            searchMode(userMessage, sink, clock, tools, sessionId, false, cutShort ? "timeout" : "empty answer");
            return;
        }

        String finalText = answer.toString().trim();
        List<Citation> cited = pickCitations(tools.citations(), finalText, tools.evidence(), userMessage);
        cited.forEach(sink::citation);
        tools.actions().forEach(sink::action);
        sink.done(new Usage("llm", tools.callCount(), tools.toolNames(), promptTokens, completionTokens, clock.ttft(), clock.total(),
                sessionId, null));

        memory.append(sessionId, userMessage, stripRefs(finalText));
        if (history.isEmpty() && !cited.isEmpty() && !cutShort && !isRefusal(finalText)) {
            answerCache.put(userMessage, new AnswerCache.Cached(finalText, cited, tools.actions()));
        }
    }

    // ------------------------------------------------------------------ one model call

    private record Round(String text, List<AssistantMessage.ToolCall> toolCalls, Integer promptTokens, Integer completionTokens,
                         boolean cutShort) {
    }

    /**
     * Stream one model reply. The circuit breaker is driven by hand: ask permission, then report success or
     * failure with the duration (slow calls count against the model too).
     *
     * <p>When tools are on offer, the first few characters are HELD BACK: if the model says "I'll search for that…"
     * and then calls a tool, that chatter is dropped instead of ending up in the answer.
     */
    private Round streamRound(Prompt prompt, Sink sink, Clock clock, StringBuilder answer, boolean toolsOffered) {
        breaker.acquirePermission();                                    // throws CallNotPermittedException when OPEN
        long start = System.nanoTime();
        StringBuilder text = new StringBuilder();
        StringBuilder held = new StringBuilder();
        boolean[] releasing = {!toolsOffered};
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        Integer[] usage = {null, null};
        ThinkFilter think = new ThinkFilter();
        try {
            Duration remaining = clock.remaining(props.timeout());
            if (remaining.isNegative() || remaining.isZero()) {
                throw new IllegalStateException("answer deadline exceeded");
            }
            // timeout(): fail if the model stays silent that long (it's down / stuck) → search mode.
            // take(): stop reading once the overall budget is spent (the answer so far is kept, marked "…").
            // takeWhile(): the reader pressed Stop → cancel the request to Ollama.
            var flux = chatModel.stream(prompt).timeout(remaining).take(remaining).takeWhile(c -> !sink.isClosed());
            for (ChatResponse chunk : flux.toIterable()) {
                if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                    var u = chunk.getMetadata().getUsage();
                    if (u.getPromptTokens() != null && u.getPromptTokens() > 0) {
                        usage[0] = u.getPromptTokens();
                    }
                    if (u.getCompletionTokens() != null && u.getCompletionTokens() > 0) {
                        usage[1] = u.getCompletionTokens();
                    }
                }
                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                    continue;
                }
                AssistantMessage out = chunk.getResult().getOutput();
                if (out.hasToolCalls()) {
                    calls.addAll(out.getToolCalls());
                }
                String piece = think.accept(out.getText());
                if (piece.isEmpty()) {
                    continue;
                }
                text.append(piece);
                if (!releasing[0]) {
                    held.append(piece);
                    // Release once it's clearly an answer: long enough and not "I'll search…".
                    if (held.length() >= 48 && !PREAMBLE.matcher(held).matches()) {
                        releasing[0] = true;
                        emit(held.toString(), sink, clock, answer);
                    }
                    continue;
                }
                emit(piece, sink, clock, answer);
            }
            if (!releasing[0] && calls.isEmpty() && !held.isEmpty()) {
                emit(held.toString(), sink, clock, answer);            // short answer, no tool call: show it
            } else if (!calls.isEmpty() && !held.isEmpty()) {
                log.debug("dropped pre-tool chatter: {}", abbreviate(held.toString()));
            }
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            boolean cut = clock.remaining(props.timeout()).isNegative() && calls.isEmpty();
            return new Round(text.toString(), calls, usage[0], usage[1], cut);
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw e;
        }
    }

    private static void emit(String piece, Sink sink, Clock clock, StringBuilder answer) {
        answer.append(piece);
        clock.firstToken();
        sink.token(piece);
    }

    private ChatOptions options(ToolCallback[] tools) {
        return OllamaChatOptions.builder()
                .model(props.chatModel())
                .disableThinking()                    // belt and braces: we use a NON-thinking (instruct) model anyway
                .temperature(0.2)                     // low = factual and repeatable, not creative
                .numPredict(props.maxOutputTokens())  // max tokens to generate
                .numCtx(props.contextWindowOrDefault())  // how much text the model can see at once (default 4096 is too small)
                .toolCallbacks(List.of(tools))
                .build();
    }

    private String runTool(Map<String, ToolCallback> byName, AssistantMessage.ToolCall call) {
        ToolCallback cb = byName.get(call.name());
        if (cb == null) {
            return "{\"error\":\"unknown tool " + call.name().replaceAll("[^A-Za-z0-9_]", "") + "\"}";
        }
        try {
            return cb.call(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
        } catch (RuntimeException e) {
            log.warn("tool {} failed: {}", call.name(), e.getMessage());
            return "{\"error\":\"tool failed, try another approach\"}";
        }
    }

    private static String toolHint(String tool) {
        return switch (tool) {
            case "getLatestPosts" -> "Checking the latest…";
            case "searchContent" -> "Searching Pulse…";
            case "getPost" -> "Reading the post…";
            case "listSections" -> "Looking around the site…";
            case "navigate" -> "Finding the page…";
            default -> "Working on it…";
        };
    }

    // ------------------------------------------------------------------ graceful degradation

    /**
     * SEARCH MODE: the model is down, slow or the breaker is open. We still help: show the sources we already
     * fetched (pre-retrieval), or run a keyword search, and return them as plain links.
     * "The chatbot degrades, it doesn't die."
     */
    private void searchMode(String question, Sink sink, Clock clock, AgentTools tools, String sessionId, boolean partial, String reason) {
        sink.status("fallback", "Switching to search mode…");
        List<Citation> hits = tools.citations().stream().limit(5).toList();
        if (hits.isEmpty()) {
            // Keyword search only: the vector half needs the SAME Ollama server that just failed (or is still busy
            // with the abandoned request) — a fallback must not depend on the thing that broke.
            List<ContentItem> found = content.fullTextSearch(question, null, 5);
            if (found.isEmpty()) {
                found = content.latestPosts(null, null, 5);
            }
            List<Citation> numbered = new ArrayList<>();
            for (ContentItem h : found) {
                numbered.add(new Citation(numbered.size() + 1, h.title(), h.url(), h.slug(), h.source(), h.section(), h.type(),
                        h.imageUrl(), !h.url().startsWith("/")));
            }
            hits = numbered;
        }
        StringBuilder sb = new StringBuilder(HarveyPersona.searchModeIntro(partial, !hits.isEmpty()));
        for (Citation h : hits) {
            sb.append("- ").append(h.title()).append(" (").append(sourceLabel(h)).append(") [").append(h.ref()).append("]\n");
        }
        streamText(sb.toString(), sink, clock);
        hits.forEach(sink::citation);
        log.info("answered in search mode ({}), {} results", reason, hits.size());
        sink.done(new Usage("search", tools.callCount(), tools.toolNames(), null, null, clock.ttft(), clock.total(), sessionId, reason));
    }

    private static String sourceLabel(Citation c) {
        if (!"Pulse".equals(c.source())) {
            return c.source();
        }
        return "FOOTBALL".equals(c.section()) ? "Pulse · Football" : "Pulse · Code & Cortex";
    }

    private static String reason(RuntimeException e) {
        String s = e.toString().toLowerCase(Locale.ROOT);
        if (s.contains("timeout") || s.contains("deadline")) {
            return "timeout";
        }
        if (s.contains("connect") || s.contains("refused")) {
            return "model unreachable";
        }
        return e.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ citations

    /**
     * Which sources to show as cards: those the answer cites as [n], plus any whose title it mentions.
     * If it cites nothing, fall back to sources whose title shares at least two distinctive words with the answer
     * (the model used them but forgot the marker). A refusal ("I can't write poems…") shows NO cards: small models
     * like to decorate refusals with a random [1].
     */
    static List<Citation> pickCitations(List<Citation> all, String answer) {
        return pickCitations(all, answer, Map.of(), "");
    }

    /**
     * ... and every [n] is CHECKED: the sentence carrying the marker must share at least two distinctive words with
     * what source n actually said ({@code evidence}). Small models sprinkle [1][2][3] on answers that came from the
     * persona ("Pulse has two sections [1][2]"); those decorative markers don't become cards.
     *
     * <p>Plus the PRIMARY source: the essay whose title matches the QUESTION ("2005 Champions League final" → "The miracle
     * of Istanbul: how Liverpool won the 2005 Champions League…") is added when the answer clearly draws on it, even if
     * the model cited a secondary source instead.
     */
    static List<Citation> pickCitations(List<Citation> all, String answer, Map<Integer, String> evidence, String question) {
        if (all.isEmpty() || answer.isBlank() || isRefusal(answer)) {
            return List.of();
        }
        Map<Integer, Citation> byRef = new LinkedHashMap<>();
        all.forEach(c -> byRef.put(c.ref(), c));
        Set<Citation> out = new LinkedHashSet<>();
        Matcher m = REF.matcher(answer);
        int sentenceStart = 0;
        String lastClaim = "";
        while (m.find()) {
            // the claim = text since the previous sentence end (or previous marker group)
            String before = answer.substring(sentenceStart, m.start());
            int cut = Math.max(Math.max(before.lastIndexOf(". "), before.lastIndexOf("\n")), Math.max(before.lastIndexOf("! "), before.lastIndexOf("? ")));
            String claim = before.isBlank() ? lastClaim                              // "[1][2]": same claim as the marker before
                    : (cut > 0 && cut < before.length() - 12 ? before.substring(cut + 1) : before).toLowerCase(Locale.ROOT);
            lastClaim = claim;
            for (String n : m.group(1).split("\\s*,\\s*")) {
                Citation c = byRef.get(Integer.parseInt(n.replaceAll("\\D", "")));
                if (c != null && supports(evidence.get(c.ref()), claim)) {
                    out.add(c);
                }
            }
            sentenceStart = m.end();
        }
        String a = answer.toLowerCase(Locale.ROOT);
        all.stream().filter(c -> c.title().length() >= 8 && a.contains(c.title().toLowerCase(Locale.ROOT))).forEach(out::add);
        Set<String> answerWords = words(a);
        Set<String> questionWords = words(question.toLowerCase(Locale.ROOT));
        // primary source: title ↔ question (≥ 2 words) and evidence ↔ answer (≥ 4 words)
        all.stream().filter(c -> !out.contains(c))
                .filter(c -> overlap(words(c.title().toLowerCase(Locale.ROOT)), questionWords) >= 2)
                .filter(c -> evidence.get(c.ref()) != null && overlap(words(evidence.get(c.ref()).toLowerCase(Locale.ROOT)), answerWords) >= 4)
                .findFirst().ifPresent(out::add);
        if (out.isEmpty()) {
            // no usable markers: sources the answer obviously used (title words, or ≥ 4 shared words with what it said)
            all.stream().filter(c -> overlap(words(c.title().toLowerCase(Locale.ROOT)), answerWords) >= 2
                            || evidence.get(c.ref()) != null && overlap(words(evidence.get(c.ref()).toLowerCase(Locale.ROOT)), answerWords) >= 4)
                    .sorted(java.util.Comparator.comparingLong((Citation c) -> -overlap(
                            words((evidence.getOrDefault(c.ref(), c.title())).toLowerCase(Locale.ROOT)), answerWords)))
                    .limit(2).forEach(out::add);
        }
        return out.stream().limit(6).toList();
    }

    /** Words that say nothing about WHICH source was used (they're everywhere on Pulse). */
    private static final Set<String> GENERIC = Set.of("pulse", "harvey", "ankur", "posts", "section", "sections", "football", "cortex",
            "about", "their", "there", "which", "these", "those", "would", "could", "other", "where", "while", "being", "after");

    private static long overlap(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).count();
    }

    static boolean supports(String evidence, String claim) {
        if (evidence == null) {
            return true;                                   // nothing to check against (e.g. tests): trust the marker
        }
        Set<String> src = words(evidence.toLowerCase(Locale.ROOT));
        return words(claim).stream().filter(src::contains).limit(2).count() >= 2;
    }

    /**
     * Distinctive words (5+ letters), cut to their first 5 letters — a poor man's STEMMER, so "partition" matches
     * "partitions" and "consumer" matches "consumers".
     */
    private static Set<String> words(String s) {
        Set<String> out = new java.util.HashSet<>();
        for (String w : s.split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 5 && !GENERIC.contains(w)) {
                out.add(w.substring(0, 5));
            }
        }
        return out;
    }

    static boolean isRefusal(String answer) {
        return REFUSAL.matcher(answer.replace('\u2019', '\'')).matches();      // curly apostrophes too: "can’t"
    }

    /** Memory keeps answers WITHOUT [n] markers: the numbers only make sense next to this turn's sources. */
    static String stripRefs(String text) {
        return REF.matcher(text).replaceAll("").replaceAll("[ \\t]+([.,;:!?])", "$1").replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static void streamText(String text, Sink sink, Clock clock) {
        for (String word : text.split("(?<=\\s)")) {
            if (!word.isEmpty()) {
                clock.firstToken();
                sink.token(word);
            }
        }
    }

    /** null-safe sum. (Careful: "b == null ? a : a + b" would UNBOX a null a — Java's ternary type rules.) */
    private static Integer add(Integer a, Integer b) {
        if (b == null) {
            return a;
        }
        return a == null ? b : Integer.valueOf(a + b);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Hides <think>…</think> reasoning if a model emits it inline despite disableThinking(). */
    static final class ThinkFilter {
        private boolean inside;

        String accept(String piece) {
            if (piece == null || piece.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            String rest = piece;
            while (!rest.isEmpty()) {
                if (inside) {
                    int end = rest.indexOf("</think>");
                    if (end < 0) {
                        return out.toString();
                    }
                    inside = false;
                    rest = rest.substring(end + "</think>".length());
                } else {
                    int start = rest.indexOf("<think>");
                    if (start < 0) {
                        out.append(rest);
                        break;
                    }
                    out.append(rest, 0, start);
                    inside = true;
                    rest = rest.substring(start + "<think>".length());
                }
            }
            return out.toString();
        }
    }

    /** Timing for "time to first token" and the overall deadline. */
    static final class Clock {
        private final long start = System.nanoTime();
        private long firstToken = -1;

        void firstToken() {
            if (firstToken < 0) {
                firstToken = System.nanoTime();
            }
        }

        long ttft() {
            return firstToken < 0 ? -1 : (firstToken - start) / 1_000_000;
        }

        long total() {
            return (System.nanoTime() - start) / 1_000_000;
        }

        Duration remaining(Duration budget) {
            return budget.minusNanos(System.nanoTime() - start);
        }
    }
}
