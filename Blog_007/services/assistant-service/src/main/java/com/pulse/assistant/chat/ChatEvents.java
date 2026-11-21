package com.pulse.assistant.chat;

import com.pulse.assistant.tools.AgentTools.Citation;
import com.pulse.assistant.tools.AgentTools.NavigateAction;

/**
 * The SSE contract with the chat widget (PLAN §13.3). Each event has a NAME and a small JSON payload:
 * <pre>
 * event: status    data: {"stage":"searching","text":"Searching Pulse…"}          (optional, cosmetic)
 * event: token     data: {"text":"Kafka "}                                          (may contain [n] = citation ref)
 * event: citation  data: {"ref":1,"title":"…","url":"/posts/…","slug":"…","source":"Pulse","section":"TECH",
 *                         "type":"POST","imageUrl":"https://…","external":false}
 * event: action    data: {"type":"NAVIGATE","path":"/football","auto":true,"label":"Football"}
 * event: done      data: {"usage":{"mode":"llm|search|cache|nav","toolCalls":1,"ttftMs":640,"totalMs":2900,"reason":null,…}}
 * event: error     data: {"message":"…"}                                            (terminal: no done follows)
 * </pre>
 * Order: status* → token* → citation* → action* → done. Changes are ADDITIVE only (new fields/events), so an
 * older chat widget keeps working: it simply ignores what it doesn't know.
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    public record Token(String text) {
    }

    /** {@code reason}: why we fell back to search mode (null otherwise). */
    public record Usage(String mode, int toolCalls, java.util.List<String> tools, Integer promptTokens, Integer completionTokens,
                        long ttftMs, long totalMs, String sessionId, String reason) {
    }

    /** What Harvey is doing right now ("searching", "tool", "fallback") — lets the UI show a live hint. */
    public record Status(String stage, String text) {
    }

    public record Done(Usage usage) {
    }

    public record Error(String message) {
    }

    /** Where events go: an SSE stream in production, a list in tests. */
    public interface Sink {
        /** Optional progress hint; ignored by default. */
        default void status(String stage, String text) {
        }

        /** True once the reader went away (Stop button, closed tab): the agent stops generating. */
        default boolean isClosed() {
            return false;
        }

        void token(String text);

        void citation(Citation c);

        void action(NavigateAction a);

        void done(Usage u);

        void error(String message);
    }
}
