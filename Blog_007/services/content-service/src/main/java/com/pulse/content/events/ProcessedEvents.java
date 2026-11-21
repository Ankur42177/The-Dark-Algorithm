package com.pulse.content.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The idempotency ledger: which event ids each consumer group has already handled. */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadyProcessed(UUID eventId, String group) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = :e AND consumer_group = :g)")
                .param("e", eventId).param("g", group).query(Boolean.class).single();
    }

    /**
     * Returns true if THIS call recorded it (false = someone already had). ON CONFLICT DO NOTHING makes
     * the insert itself idempotent, so two racing deliveries can't both "win".
     */
    public boolean markProcessed(UUID eventId, String group) {
        return jdbc.sql("INSERT INTO processed_events (event_id, consumer_group) VALUES (:e, :g) ON CONFLICT DO NOTHING")
                .param("e", eventId).param("g", group).update() == 1;
    }
}
