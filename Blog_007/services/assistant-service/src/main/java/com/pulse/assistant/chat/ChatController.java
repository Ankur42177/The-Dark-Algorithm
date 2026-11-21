package com.pulse.assistant.chat;

import com.pulse.assistant.tools.AgentTools.Citation;
import com.pulse.assistant.tools.AgentTools.NavigateAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * POST /api/v1/assistant/chat → a stream of Server-Sent Events.
 *
 * <p>SSE = one long HTTP response the server keeps writing small "event: …\ndata: …\n\n" blocks into.
 * One-directional (server → browser), plain HTTP, works through proxies — exactly what streaming an answer
 * needs. (WebSockets would be two-way and heavier.) The reader sees the first words after ~1 s instead of
 * waiting for the whole answer: streaming HIDES the model's latency.
 */
@RestController
@Tag(name = "Assistant", description = "Ask Pulse — streaming AI agent")
public class ChatController {

    public record ChatRequest(
            @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String sessionId,
            @NotBlank @Size(max = 1000) String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentService agent;
    private final ExecutorService chatExecutor;

    public ChatController(AgentService agent, ExecutorService chatExecutor) {
        this.agent = agent;
        this.chatExecutor = chatExecutor;
    }

    @PostMapping(value = "/api/v1/assistant/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask a question; answer streams as SSE events: token, citation, action, done, error")
    public SseEmitter chat(@Valid @RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(30_000L);            // > the 20 s answer budget
        String sessionId = req.sessionId() != null ? req.sessionId() : UUID.randomUUID().toString();
        // The HTTP thread returns immediately; the agent runs on a virtual thread and pushes events.
        chatExecutor.submit(() -> {
            try {
                agent.answer(sessionId, req.message().strip(), new SseSink(emitter));
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("chat failed", e);
                new SseSink(emitter).error("Something went wrong. Please try again.");
                emitter.complete();
            }
        });
        return emitter;
    }

    /** Adapts the agent's events to SSE. If the browser went away, sends fail quietly. */
    static final class SseSink implements ChatEvents.Sink {
        private final SseEmitter emitter;
        private volatile boolean closed;

        SseSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private void send(String name, Object data) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                closed = true;                                    // client disconnected
            }
        }

        @Override public void token(String text) { send("token", new ChatEvents.Token(text)); }
        @Override public void citation(Citation c) { send("citation", c); }
        @Override public void action(NavigateAction a) { send("action", a); }
        @Override public void done(ChatEvents.Usage u) { send("done", new ChatEvents.Done(u)); }
        @Override public void error(String message) { send("error", new ChatEvents.Error(message)); }
    }
}
