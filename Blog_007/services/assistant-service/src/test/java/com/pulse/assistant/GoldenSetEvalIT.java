package com.pulse.assistant;

import com.pulse.assistant.chat.AgentService;
import com.pulse.assistant.chat.ChatEvents;
import com.pulse.assistant.embed.EmbedderService;
import com.pulse.assistant.support.StubContentService;
import com.pulse.assistant.tools.AgentTools;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVALS — how teams test agents. A real LLM is non-deterministic, so instead of exact-output assertions we
 * run a fixed "golden set" of questions and measure the PASS RATE (right source cited? key facts present? right
 * page opened? no leaks?). Uses the REAL local Ollama models and a snapshot of the REAL site content.
 * Excluded from the normal build; run with -Pevals.
 *
 * <p>Eval design rules (learned the hard way — a first run on a loaded laptop scored 2/20 because the circuit
 * breaker opened after two slow answers and every later case was answered in search mode in ~30 ms):
 * <ul>
 *   <li>The LLM circuit breaker is RESET before every case, so each case really reaches the model.
 *       (Production breaker behaviour is unchanged; this is only the test harness.)</li>
 *   <li>A WARM-UP call loads the models into memory first, so cold model loading isn't counted as latency.</li>
 *   <li>Cases answered in search mode are INFRA (the model wasn't measured), not FAIL. Three separate scores:
 *       ANSWER QUALITY (llm + nav cases), CITATION HIT RATE (cases that name a source), and the FALLBACK RATE.</li>
 *   <li>The per-answer budget is raised to 60 s: we measure what the model says, latency is REPORTED (p50/p95),
 *       not used to fail cases. Production keeps its 20 s budget.</li>
 * </ul>
 */
@Tag("evals")
@SpringBootTest(properties = {"spring.profiles.active=api", "pulse.embedder.kafka-enabled=false", "pulse.assistant.timeout=60s"})
class GoldenSetEvalIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18").withDatabaseName("assistant_db");
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);
    static final StubContentService CONTENT;

    static {
        POSTGRES.start();
        REDIS.start();
        try {
            CONTENT = StubContentService.fromSnapshot("/evals/content-snapshot.json");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pulse.assistant.content-service-url", CONTENT::url);
    }

    @AfterAll
    static void stop() {
        CONTENT.stop();
    }

    @Autowired AgentService agent;
    @Autowired EmbedderService embedder;
    @Autowired CircuitBreaker llmCircuitBreaker;
    @Autowired ChatModel chatModel;
    @Autowired EmbeddingModel embeddingModel;

    @Test
    @SuppressWarnings("unchecked")
    void goldenSet() throws Exception {
        long t0 = System.currentTimeMillis();
        EmbedderService.ReconcileReport index = embedder.reconcile(false);       // real nomic-embed-text vectors
        System.out.printf("INDEX  %d posts, %d articles, %d chunks in %d ms%n", index.postsOnSite(), index.articlesOnSite(),
                index.chunksWritten(), System.currentTimeMillis() - t0);
        embeddingModel.embed("search_query: warm-up");             // load the models into memory BEFORE timing
        chatModel.call("Reply with the single word: ready");
        List<Map<String, Object>> cases;
        try (InputStream in = getClass().getResourceAsStream("/evals/golden.yaml")) {
            cases = new Yaml().load(in);
        }
        int pass = 0, fail = 0, infra = 0, noAnswer = 0, citeCases = 0, citeHits = 0;
        List<Long> totals = new ArrayList<>();
        List<Long> ttfts = new ArrayList<>();
        List<String> report = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            llmCircuitBreaker.reset();                             // each case must reach the model
            Capture cap = new Capture();
            String q = (String) c.get("question");
            agent.answer(UUID.randomUUID().toString(), q, cap);
            String text = cap.text.toString();
            String verdict;
            List<String> why = new ArrayList<>();
            if (cap.usage == null) {
                verdict = "FAIL";                                  // no done event at all: a real failure
                why.add("no done event");
                noAnswer++;
                fail++;
            } else if ("search".equals(cap.usage.mode())) {
                verdict = "INFRA";                                 // fallback answered: model not measured
                why.add("search mode: " + cap.usage.reason());
                infra++;
            } else {
                if (c.get("cite") != null) {
                    citeCases++;
                    Pattern p = Pattern.compile((String) c.get("cite"));
                    if (cap.citations.stream().anyMatch(ct -> p.matcher(ct.url()).find())) {
                        citeHits++;
                    } else {
                        why.add("cite " + c.get("cite"));
                    }
                }
                if (Boolean.TRUE.equals(c.get("citeAny")) && cap.citations.isEmpty()) {
                    why.add("no citation");
                }
                if (Boolean.TRUE.equals(c.get("noCite")) && !cap.citations.isEmpty()) {
                    why.add("unexpected citations");
                }
                for (String rx : (List<String>) c.getOrDefault("expect", List.of())) {
                    if (!Pattern.compile(rx, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                        why.add("expect /" + rx + "/");
                    }
                }
                if (c.get("forbid") != null && Pattern.compile((String) c.get("forbid"), Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                    why.add("forbidden /" + c.get("forbid") + "/");
                }
                List<String> tools = (List<String>) c.get("tools");
                if (tools != null && cap.usage.tools().stream().noneMatch(tools::contains)) {
                    why.add("tools " + tools);
                }
                if (c.get("action") != null && cap.actions.stream().noneMatch(a -> a.path().equals(c.get("action")))) {
                    why.add("action " + c.get("action"));
                }
                verdict = why.isEmpty() ? "PASS" : "FAIL";
                if (why.isEmpty()) pass++; else fail++;
                totals.add(cap.usage.totalMs());
                if (cap.usage.ttftMs() >= 0) ttfts.add(cap.usage.ttftMs());
            }
            report.add(String.format("%-5s %-62s mode=%-6s ttft=%5dms total=%6dms tools=%s cites=%s %s%n        → %s", verdict, q,
                    cap.usage == null ? "-" : cap.usage.mode(), cap.usage == null ? -1 : cap.usage.ttftMs(),
                    cap.usage == null ? -1 : cap.usage.totalMs(), cap.usage == null ? "-" : cap.usage.tools(),
                    cap.citations.stream().map(ct -> ct.url().replaceAll("^/posts/|^https?://", "")).map(u -> u.length() > 40 ? u.substring(0, 40) : u).toList(),
                    why.isEmpty() ? "" : "WHY " + why, text.replaceAll("\\s+", " ").substring(0, Math.min(220, text.replaceAll("\\s+", " ").length()))));
        }
        report.forEach(System.out::println);
        int measured = pass + fail - noAnswer;
        System.out.printf(Locale.ROOT, "GOLDEN SET  quality: %d/%d passed (%.0f%%) · citation hit rate: %d/%d (%.0f%%) · fallback (search mode): %d/%d (%.0f%%) · "
                        + "no answer: %d · TTFT p50 %d ms p95 %d ms · total p50 %d ms p95 %d ms%n",
                pass, measured, pct(pass, measured), citeHits, citeCases, pct(citeHits, citeCases), infra, cases.size(), pct(infra, cases.size()),
                noAnswer, percentile(ttfts, 50), percentile(ttfts, 95), percentile(totals, 50), percentile(totals, 95));
        assertThat(noAnswer).as("every case must get an answer (llm, nav or fallback)").isZero();
        assertThat(measured).as("at least half the cases must reach the model, else the run measured infrastructure").isGreaterThanOrEqualTo(cases.size() / 2);
        assertThat((double) pass / Math.max(1, measured)).as("answer-quality pass rate").isGreaterThanOrEqualTo(0.7);
    }

    private static double pct(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> s = values.stream().sorted().toList();
        int idx = (int) Math.ceil(p / 100.0 * s.size()) - 1;                // nearest-rank method
        return s.get(Math.max(0, Math.min(s.size() - 1, idx)));
    }

    static final class Capture implements ChatEvents.Sink {
        final StringBuilder text = new StringBuilder();
        final List<AgentTools.Citation> citations = new ArrayList<>();
        final List<AgentTools.NavigateAction> actions = new ArrayList<>();
        ChatEvents.Usage usage;

        @Override public void token(String t) { text.append(t); }
        @Override public void citation(AgentTools.Citation c) { citations.add(c); }
        @Override public void action(AgentTools.NavigateAction a) { actions.add(a); }
        @Override public void done(ChatEvents.Usage u) { usage = u; }
        @Override public void error(String message) { }
    }
}
