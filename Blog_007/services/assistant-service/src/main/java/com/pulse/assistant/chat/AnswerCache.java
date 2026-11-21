package com.pulse.assistant.chat;

import com.pulse.assistant.config.AssistantProperties;
import com.pulse.assistant.tools.AgentTools.Citation;
import com.pulse.assistant.tools.AgentTools.NavigateAction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Answer cache: chat:answer:{sha256(normalised question)} for 10 minutes (PLAN §10).
 * Many readers ask the same thing ("what's new in AI?"); an LLM answer costs seconds and money, a Redis GET
 * costs a millisecond. Only FIRST questions of a session are cached — follow-ups depend on history.
 */
@Component
public class AnswerCache {

    public record Cached(String answer, List<Citation> citations, List<NavigateAction> actions) {
    }

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final AssistantProperties props;

    public AnswerCache(StringRedisTemplate redis, JsonMapper json, AssistantProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    public Optional<Cached> get(String question) {
        try {
            String v = redis.opsForValue().get(key(question));
            return v == null ? Optional.empty() : Optional.of(json.readValue(v, Cached.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void put(String question, Cached answer) {
        try {
            redis.opsForValue().set(key(question), json.writeValueAsString(answer), props.answerCacheTtl());
        } catch (RuntimeException e) {
            // cache is optional
        }
    }

    /** "  What's NEW in AI this week?? " and "what's new in ai this week" share one entry. */
    static String normalise(String q) {
        return q.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    static String key(String question) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(normalise(question).getBytes(StandardCharsets.UTF_8));
            return "chat:answer:" + HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
