package com.pulse.assistant.chat;

import com.pulse.assistant.config.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Short-term chat memory in a Redis LIST: chat:mem:{sessionId} (PLAN §10). Only the last N messages are
 * kept (LTRIM) and the whole list expires after 24 h of inactivity. No personal data — just the text.
 * Stateless service, state in Redis ⇒ any replica can continue any conversation.
 */
@Component
public class ChatMemoryStore {

    public record Turn(String role, String text) {
    }

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryStore.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final AssistantProperties props;

    public ChatMemoryStore(StringRedisTemplate redis, JsonMapper json, AssistantProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    public List<Turn> load(String sessionId) {
        try {
            List<String> raw = redis.opsForList().range(key(sessionId), 0, -1);
            return raw == null ? List.of() : raw.stream().map(s -> json.readValue(s, Turn.class)).toList();
        } catch (RuntimeException e) {
            log.warn("chat memory unavailable: {}", e.getMessage());   // fail open: answer without history
            return List.of();
        }
    }

    public void append(String sessionId, String userText, String assistantText) {
        try {
            String k = key(sessionId);
            redis.opsForList().rightPushAll(k, json.writeValueAsString(new Turn("user", userText)),
                    json.writeValueAsString(new Turn("assistant", assistantText)));
            redis.opsForList().trim(k, -props.memoryTurns(), -1);      // keep only the newest N
            redis.expire(k, props.memoryTtl());
        } catch (RuntimeException e) {
            log.warn("could not save chat memory: {}", e.getMessage());
        }
    }

    private static String key(String sessionId) {
        return "chat:mem:" + sessionId;
    }
}
