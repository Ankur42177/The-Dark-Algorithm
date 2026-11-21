package com.pulse.assistant.chat;

import com.pulse.assistant.tools.AgentTools.Citation;
import com.pulse.assistant.tools.AgentTools.NavigateAction;

/**
 * The SSE contract with the chat widget (PLAN §13.3). Each event has a NAME and a small JSON payload:
 * <pre>
 * event: token     data: {"text":"Kafka "}
 * event: citation  data: {"title":"…","url":"/posts/…","slug":"…","source":"Pulse"}
 * event: action    data: {"type":"NAVIGATE","path":"/football","auto":true}
 * event: done      data: {"usage":{"mode":"llm","toolCalls":1,"ttftMs":640,"totalMs":2900,…}}
 * event: error     data: {"message":"…"}
 * </pre>
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    public record Token(String text) {
    }

    public record Usage(String mode, int toolCalls, java.util.List<String> tools, Integer promptTokens, Integer completionTokens,
                        long ttftMs, long totalMs, String sessionId) {
    }

    public record Done(Usage usage) {
    }

    public record Error(String message) {
    }

    /** Where events go: an SSE stream in production, a list in tests. */
    public interface Sink {
        void token(String text);

        void citation(Citation c);

        void action(NavigateAction a);

        void done(Usage u);

        void error(String message);
    }
}
