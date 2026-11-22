package com.pulse.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Every "pulse.assistant.*" setting, typed. Guardrails live here so they're visible and tunable.
 *
 * <p>Spring note: {@code @ConfigurationProperties} binds YAML keys to this record's components
 * (kebab-case "max-tool-calls" → {@code maxToolCalls}). A missing number becomes 0 and a missing object null,
 * which is why the "...OrDefault()" helpers below exist.
 *
 * @param contentServiceUrl base URL of content-service (tools read posts through its API, never its DB)
 * @param chatModel         Ollama model name for answers (e.g. qwen3:4b-instruct)
 * @param maxToolCalls      hard cap on MODEL-requested tool calls per user message (PLAN §13.6: 4)
 * @param maxOutputTokens   cap on generated tokens per answer
 * @param timeout           overall budget for one answer (PLAN §13.6: 20 s)
 * @param memoryTurns       how many past messages per session are sent back to the model
 * @param memoryTtl         chat memory lifetime in Redis (24 h)
 * @param answerCacheTtl    cache for identical first questions (10 min)
 * @param corsOrigins       browser origins allowed to call the chat endpoint directly
 * @param contextWindow     tokens the model may look at (Ollama num_ctx). Too small = the system prompt gets cut off!
 * @param embedTimeout      give up on the embedding model after this long and use keyword search only
 * @param preRetrieval      search BEFORE asking the model (RAG-first); small models often forget to call tools
 */
@ConfigurationProperties("pulse.assistant")
public record AssistantProperties(
        String contentServiceUrl,
        String chatModel,
        int maxToolCalls,
        int maxOutputTokens,
        Duration timeout,
        int memoryTurns,
        Duration memoryTtl,
        Duration answerCacheTtl,
        List<String> corsOrigins,
        int contextWindow,
        Duration embedTimeout,
        Boolean preRetrieval) {

    public Duration embedTimeoutOrDefault() {
        return embedTimeout == null ? Duration.ofSeconds(4) : embedTimeout;
    }

    public int contextWindowOrDefault() {
        return contextWindow <= 0 ? 8192 : contextWindow;
    }

    public boolean preRetrievalEnabled() {
        return preRetrieval == null || preRetrieval;
    }
}
