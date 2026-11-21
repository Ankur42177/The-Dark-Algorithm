package com.pulse.content.admin;

import com.pulse.content.events.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Publishes SCHEDULED posts whose time has come. Safe with many replicas WITHOUT a lock:
 * a single UPDATE … RETURNING is atomic, so each post flips to PUBLISHED exactly once and only the
 * replica whose UPDATE changed the row gets its slug back. That replica writes the PostPublished outbox
 * event in the same transaction, so exactly one event is produced per scheduled publish.
 */
@Component
public class ScheduledPublisher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPublisher.class);

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public ScheduledPublisher(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    record Published(UUID id, String slug, String section, String title, String summary, List<String> tags, Instant publishedAt) {
    }

    @Scheduled(fixedDelayString = "${pulse.scheduler.publish-interval:30s}")
    @Transactional
    public List<String> publishDuePosts() {
        List<Published> rows = jdbc.sql("""
                        UPDATE posts p SET status = 'PUBLISHED', updated_at = now(), version = version + 1
                         WHERE status = 'SCHEDULED' AND published_at <= now()
                        RETURNING id, slug, section, title, summary, published_at,
                                  ARRAY(SELECT t.name FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
                                         WHERE pt.post_id = p.id ORDER BY t.name) AS tags""")
                .query((rs, n) -> new Published(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("section"),
                        rs.getString("title"), rs.getString("summary"),
                        Arrays.asList((String[]) rs.getArray("tags").getArray()), rs.getTimestamp("published_at").toInstant()))
                .list();
        for (Published r : rows) {
            outbox.postPublished(r.id(), r.slug(), r.section(), r.title(), r.summary(), r.tags(), r.publishedAt());
            log.info("Scheduled post published: {}", r.slug());
        }
        return rows.stream().map(Published::slug).toList();
    }
}
