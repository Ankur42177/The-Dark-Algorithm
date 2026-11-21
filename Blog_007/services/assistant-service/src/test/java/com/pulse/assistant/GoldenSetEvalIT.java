package com.pulse.assistant;

import com.pulse.assistant.chat.AgentService;
import com.pulse.assistant.chat.ChatEvents;
import com.pulse.assistant.embed.EmbedderService;
import com.pulse.assistant.support.StubContentService;
import com.pulse.assistant.tools.AgentTools;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVALS — how teams test agents. A real LLM is non-deterministic, so instead of exact-output assertions we
 * run a fixed "golden set" of questions and measure the PASS RATE (did it pick the right tool, cite the right
 * post, open the right page?). Uses the REAL local Ollama models; content comes from the stub service.
 * Excluded from the normal build; run with -Pevals.
 *
 * <p>Eval design rules (learned the hard way — a first run on a loaded laptop scored 2/20 because the circuit
 * breaker opened after two slow answers and every later case was answered in search mode in ~30 ms):
 * <ul>
 *   <li>The LLM circuit breaker is RESET before every case, so each case really reaches the model.
 *       (Production breaker behaviour is unchanged; this is only the test harness.)</li>
 *   <li>A WARM-UP call loads the models into memory first, so cold model loading isn't counted as TTFT.</li>
 *   <li>Cases answered in search mode are INFRA (the model wasn't measured), not FAIL. We report two scores:
 *       MODEL QUALITY (llm-mode cases only) and RESILIENCE (did every case still get an answer).</li>
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
            CONTENT = new StubContentService();
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
        embedder.backfill();                                       // real nomic-embed-text vectors
        embeddingModel.embed("search_query: warm-up");             // load the models into memory BEFORE timing
        chatModel.call("Reply with the single word: ready");
        List<Map<String, Object>> cases;
        try (InputStream in = getClass().getResourceAsStream("/evals/golden.yaml")) {
            cases = new Yaml().load(in);
        }
        int pass = 0, fail = 0, infra = 0, answered = 0, llmCases = 0;
        long ttftSum = 0, totalSum = 0;
        List<String> report = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            llmCircuitBreaker.reset();                             // each case must reach the model
            Capture cap = new Capture();
            agent.answer(UUID.randomUUID().toString(), (String) c.get("question"), cap);
            String verdict;
            if (cap.usage == null) {
                verdict = "FAIL";                                  // no done event at all: a real failure
                fail++;
            } else if (!"llm".equals(cap.usage.mode())) {
                verdict = "INFRA";                                 // fallback answered: model not measured
                infra++;
                answered++;
            } else {
                answered++;
                List<String> expectedTools = (List<String>) c.get("tools");
                boolean toolOk = cap.usage.tools().stream().anyMatch(expectedTools::contains);
                boolean citeOk = c.get("cite") == null || cap.citations.stream().anyMatch(ct -> ct.url().contains((String) c.get("cite")));
                boolean actionOk = c.get("action") == null || cap.actions.stream().anyMatch(a -> a.path().equals(c.get("action")));
                verdict = toolOk && citeOk && actionOk ? "PASS" : "FAIL";
                if ("PASS".equals(verdict)) pass++; else fail++;
                llmCases++;
                ttftSum += cap.usage.ttftMs();
                totalSum += cap.usage.totalMs();
            }
            report.add(String.format("%-5s %-55s tools=%s ttft=%dms total=%dms mode=%s", verdict, c.get("question"),
                    cap.usage == null ? "-" : cap.usage.tools(), cap.usage == null ? -1 : cap.usage.ttftMs(),
                    cap.usage == null ? -1 : cap.usage.totalMs(), cap.usage == null ? "-" : cap.usage.mode()));
        }
        report.forEach(System.out::println);
        int measured = pass + fail;
        System.out.printf("GOLDEN SET  model quality: %d/%d llm-mode cases passed (%.0f%%) · infra/fallback: %d · "
                        + "resilience: %d/%d answered · avg TTFT %d ms · avg total %d ms%n",
                pass, llmCases, llmCases == 0 ? 0.0 : 100.0 * pass / llmCases, infra, answered, cases.size(),
                ttftSum / Math.max(1, llmCases), totalSum / Math.max(1, llmCases));
        assertThat(answered).as("every case must get an answer (llm or fallback)").isEqualTo(cases.size());
        assertThat(llmCases).as("at least half the cases must reach the model, else the run measured infrastructure").isGreaterThanOrEqualTo(cases.size() / 2);
        assertThat((double) pass / Math.max(1, llmCases)).as("model-quality pass rate").isGreaterThanOrEqualTo(0.7);
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
