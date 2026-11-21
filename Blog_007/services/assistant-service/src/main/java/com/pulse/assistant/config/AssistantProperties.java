package com.pulse.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Every "pulse.assistant.*" setting, typed. Guardrails live here so they're visible and tunable.
 *
 * @param contentServiceUrl base URL of content-service (tools read posts through its API, never its DB)
 * @param chatModel         Ollama model name for answers (e.g. qwen3:4b)
 * @param maxToolCalls      hard cap on tool calls per user message (PLAN §13.6: 4)
 * @param maxOutputTokens   cap on generated tokens per answer
 * @param timeout           overall budget for one answer (PLAN §13.6: 20 s)
 * @param memoryTurns       how many past messages per session are sent back to the model
 * @param memoryTtl         chat memory lifetime in Redis (24 h)
 * @param answerCacheTtl    cache for identical first questions (10 min)
 * @param corsOrigins       browser origins allowed to call the chat endpoint directly
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
        List<String> corsOrigins) {
}
