package com.pulse.assistant.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A scriptable stand-in for the LLM: tests decide what the "model" answers for each prompt, so agent
 * behaviour (tool loop, budget, guardrails, fallback) is tested deterministically and in milliseconds.
 */
public class FakeChatModel implements ChatModel {

    public final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    public volatile Function<Prompt, List<ChatResponse>> script = p -> List.of(text("Hello."));

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ChatResponse> r = stream(prompt).collectList().block();
        return r.getLast();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        return Flux.defer(() -> Flux.fromIterable(script.apply(prompt)));
    }

    public void reset() {
        prompts.clear();
        script = p -> List.of(text("Hello."));
    }

    public static ChatResponse text(String t) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(t).build())));
    }

    public static ChatResponse toolCall(String name, String argsJson) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, argsJson);
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
    }

    /** Were tools offered to the model in this prompt? */
    public static boolean toolsOffered(Prompt p) {
        return p.getOptions() instanceof ToolCallingChatOptions o && o.getToolCallbacks() != null && !o.getToolCallbacks().isEmpty();
    }

    /** Has a tool already answered in this prompt (i.e. we're in round 2+)? */
    public static boolean hasToolResults(Prompt p) {
        return p.getInstructions().stream().anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage);
    }
}
