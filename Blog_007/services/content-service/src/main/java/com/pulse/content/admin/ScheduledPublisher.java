package com.pulse.content.admin;

import com.pulse.content.post.PostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publishes SCHEDULED posts whose time has come. Safe with many replicas WITHOUT a lock:
 * a single UPDATE … RETURNING is atomic, so each post flips to PUBLISHED exactly once and only the
 * replica whose UPDATE changed the row gets its slug back.
 */
@Component
public class ScheduledPublisher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPublisher.class);

    private final JdbcClient jdbc;
    private final PostService readerSide;

    public ScheduledPublisher(JdbcClient jdbc, PostService readerSide) {
        this.jdbc = jdbc;
        this.readerSide = readerSide;
    }

    @Scheduled(fixedDelayString = "${pulse.scheduler.publish-interval:30s}")
    public List<String> publishDuePosts() {
        List<String> slugs = jdbc.sql("""
                        UPDATE posts SET status = 'PUBLISHED', updated_at = now(), version = version + 1
                         WHERE status = 'SCHEDULED' AND published_at <= now()
                        RETURNING slug""")
                .query(String.class).list();
        slugs.forEach(slug -> {
            log.info("Scheduled post published: {}", slug);
            readerSide.evict(slug);
        });
        return slugs;
    }
}
