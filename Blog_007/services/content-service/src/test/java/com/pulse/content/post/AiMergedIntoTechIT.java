package com.pulse.content.post;

import com.pulse.content.IntegrationTestBase;
import com.pulse.content.admin.AdminDtos.PostRequest;
import com.pulse.content.admin.AdminPostService;
import com.pulse.content.cache.AiSectionRedisCleanup;
import com.pulse.content.cache.CacheKeys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 merged the AI section into TECH. We test the migration itself on a SEPARATE scratch database:
 * migrate to V5, insert AI rows the old way, migrate to V6, check — exactly what happens to a real database
 * that already has data. Plus: the app maps "AI" → TECH on every write, and the Redis leftovers are merged.
 */
@SuppressWarnings("deprecation")   // Section.AI is deprecated; using it is the point of these tests
class AiMergedIntoTechIT extends IntegrationTestBase {

    @Autowired JdbcClient jdbc;
    @Autowired AdminPostService admin;
    @Autowired StringRedisTemplate redis;
    @Autowired AiSectionRedisCleanup redisCleanup;

    @Test
    void migrationV6MovesEveryAiPostAndFeedItemToTech() {
        String db = "v6_check_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("CREATE DATABASE " + db).update();                    // CREATE DATABASE can't run in a transaction: autocommit
        String url = POSTGRES.getJdbcUrl().replace("/content_db", "/" + db);
        DriverManagerDataSource ds = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcClient scratch = JdbcClient.create(ds);

        flyway(ds, "5").migrate();                                    // the schema as it was before V6
        scratch.sql("""
                INSERT INTO posts (slug, title, body_md, body_html, section, status, published_at)
                VALUES ('old-ai-post', 'Old AI post', 'x', '<p>x</p>', 'AI', 'PUBLISHED', now()),
                       ('old-ai-draft', 'Old AI draft', 'x', '<p>x</p>', 'AI', 'DRAFT', NULL),
                       ('old-tech-post', 'Old tech post', 'x', '<p>x</p>', 'TECH', 'PUBLISHED', now())""").update();
        scratch.sql("""
                INSERT INTO feed_items (source_id, source_name, url, url_hash, title, section, published_at)
                VALUES (gen_random_uuid(), 'Blog', 'https://e.x/1', repeat('a', 64), 'Old AI article', 'AI', now())""").update();

        flyway(ds, "6").migrate();

        assertThat(scratch.sql("SELECT count(*) FROM posts WHERE section = 'AI'").query(Long.class).single()).isZero();
        assertThat(scratch.sql("SELECT count(*) FROM feed_items WHERE section = 'AI'").query(Long.class).single()).isZero();
        assertThat(scratch.sql("SELECT count(*) FROM posts WHERE section = 'TECH'").query(Long.class).single()).isEqualTo(3);
        assertThat(scratch.sql("SELECT version FROM posts WHERE slug = 'old-ai-post'").query(Long.class).single()).isEqualTo(1);
        // backward compatible: the CHECK constraint still ACCEPTS 'AI' (late events from other services won't fail)
        scratch.sql("""
                INSERT INTO posts (slug, title, body_md, body_html, section, status)
                VALUES ('late-ai', 't', 'x', '<p>x</p>', 'AI', 'DRAFT')""").update();
    }

    @Test
    void theRunningDatabaseHasNoAiRowsAndNewWritesAreMappedToTech() {
        assertThat(jdbc.sql("SELECT count(*) FROM posts WHERE section = 'AI'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM feed_items WHERE section = 'AI'").query(Long.class).single()).isZero();

        var created = admin.create(new PostRequest("Legacy AI section post", null, null, "body", Section.AI, List.of(), null, null));
        assertThat(created.section()).isEqualTo(Section.TECH);
        assertThat(Section.canonical("AI")).isEqualTo("TECH");
        assertThat(Section.canonical((Section) null)).isNull();
    }

    @Test
    void redisCleanupMergesTheAiTrendingBoardIntoTech() throws Exception {
        String ai = CacheKeys.trending(Section.AI);
        String tech = CacheKeys.trending(Section.TECH);
        redis.delete(List.of(ai, tech));
        redis.opsForZSet().add(ai, "rag-in-one-picture", 5);
        redis.opsForZSet().add(tech, "rag-in-one-picture", 2);
        redis.opsForZSet().add(tech, "kafka-consumer-groups-explained", 1);

        redisCleanup.run(null);
        redisCleanup.run(null);                                       // second run: nothing left to merge

        assertThat(redis.hasKey(ai)).isFalse();
        assertThat(redis.opsForZSet().score(tech, "rag-in-one-picture")).isEqualTo(7.0);
        assertThat(redis.opsForZSet().score(tech, "kafka-consumer-groups-explained")).isEqualTo(1.0);
    }

    @Test
    void concurrentStartsMergeTheAiBoardOnlyOnce() throws Exception {
        // Several replicas start at the same moment after V6 (rolling deploy / scale-up). The first version
        // checked hasKey → ZUNIONSTORE → DELETE, so two replicas could both add the AI scores (counted double).
        String ai = CacheKeys.trending(Section.AI);
        String tech = CacheKeys.trending(Section.TECH);
        redis.delete(List.of(ai, tech));
        redis.opsForZSet().add(ai, "rag-in-one-picture", 5);
        redis.opsForZSet().add(tech, "rag-in-one-picture", 2);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        var runs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 8; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                redisCleanup.run(null);
                return null;
            }));
        }
        start.countDown();                                            // release all 8 "replicas" at once
        for (var r : runs) {
            r.get();
        }
        pool.shutdown();

        assertThat(redis.hasKey(ai)).isFalse();
        assertThat(redis.opsForZSet().score(tech, "rag-in-one-picture")).isEqualTo(7.0);   // 2 + 5, not 2 + 5 + 5
        assertThat(redis.keys(ai + ":merging:*")).isEmpty();           // the private copy is cleaned up
    }

    @Test
    @SuppressWarnings("deprecation")
    void sectionNamesFromOtherServicesAreNormalised() {
        // a late "ai" / " Tech " event must land in TECH instead of violating the CHECK constraint (→ DLT)
        assertThat(Section.canonical("AI")).isEqualTo("TECH");
        assertThat(Section.canonical(" ai ")).isEqualTo("TECH");
        assertThat(Section.canonical("football")).isEqualTo("FOOTBALL");
        assertThat(Section.canonical((String) null)).isNull();
        assertThat(Section.canonical(Section.AI)).isEqualTo(Section.TECH);
    }

    private static Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }
}
