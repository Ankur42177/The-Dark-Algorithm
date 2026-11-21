package com.pulse.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded source list after every Flyway migration has run (V2 seed, V3 + V4 curation).
 *
 * <p>Other ITs share this database and create their own sources on {@code http://localhost:<wiremock>} (some in the
 * AI section, and IngestionAdminIT pauses everything before each test), so these assertions only look at the real,
 * seeded URLs and at {@code disabled_reason}, which no other test touches.
 */
class FeedSourceCurationIT extends IngestionTestBase {

    private static final String SEEDED = "feed_url NOT LIKE 'http://localhost:%'";

    private static final List<String> INDIVIDUAL_BLOGS = List.of(
            "https://newsletter.pragmaticengineer.com/feed",
            "https://blog.bytebytego.com/feed",
            "https://martinfowler.com/feed.atom",
            "https://jvns.ca/atom.xml",
            "https://simonwillison.net/atom/entries/",
            "https://lilianweng.github.io/index.xml",
            "https://magazine.sebastianraschka.com/feed",
            "https://huyenchip.com/feed.xml",
            "https://karpathy.github.io/feed.xml");

    private static final List<String> OFFICIAL_BLOGS = List.of(
            "https://netflixtechblog.com/feed",
            "https://blog.cloudflare.com/rss/",
            "https://spring.io/blog.atom",
            "https://huggingface.co/blog/feed.xml",
            "https://www.langchain.com/blog/rss.xml",
            "https://claude.com/blog/rss.xml",
            "https://blog.google/technology/ai/rss/",
            "https://research.google/blog/rss/",
            "https://deepmind.google/blog/rss.xml");

    @Autowired JdbcClient jdbc;

    @Test
    void aiSectionIsMergedIntoTech() {
        assertThat(jdbc.sql("SELECT count(*) FROM feed_sources WHERE section = 'AI' AND " + SEEDED)
                .query(Long.class).single()).isZero();
    }

    @Test
    void individualBloggersArePausedWithAReason() {
        var rows = jdbc.sql("SELECT feed_url, active, disabled_reason FROM feed_sources WHERE feed_url IN (:urls)")
                .param("urls", INDIVIDUAL_BLOGS)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getBoolean(2), rs.getString(3)}).list();

        assertThat(rows).hasSize(INDIVIDUAL_BLOGS.size());
        assertThat(rows).allSatisfy(r -> {
            assertThat((Boolean) r[1]).as("%s active", r[0]).isFalse();
            assertThat((String) r[2]).as("%s reason", r[0]).contains("individual blog");
        });
    }

    @Test
    void techIsOfficialCompanyBlogsOnly() {
        var techUrls = jdbc.sql("SELECT feed_url FROM feed_sources WHERE section = 'TECH' AND disabled_reason IS NULL AND " + SEEDED)
                .query(String.class).list();
        assertThat(techUrls).containsExactlyInAnyOrderElementsOf(OFFICIAL_BLOGS);
    }

    @Test
    void newOfficialSourcesArePoliteAndNamed() {
        record Row(String name, int interval) {
        }
        var rows = jdbc.sql("""
                        SELECT name, fetch_interval_min FROM feed_sources
                        WHERE feed_url IN ('https://www.langchain.com/blog/rss.xml', 'https://claude.com/blog/rss.xml',
                                           'https://blog.google/technology/ai/rss/', 'https://research.google/blog/rss/',
                                           'https://deepmind.google/blog/rss.xml')""")
                .query((rs, n) -> new Row(rs.getString(1), rs.getInt(2))).list();

        assertThat(rows).extracting(Row::name).containsExactlyInAnyOrder(
                "LangChain Blog", "Claude (Anthropic)", "Google AI", "Google Research", "Google DeepMind");
        assertThat(rows).extracting(Row::interval).containsOnly(60);
    }

    @Test
    void footballKeepsItsAnalysisSourcesAndWiresStayPaused() {
        var active = jdbc.sql("SELECT name FROM feed_sources WHERE section = 'FOOTBALL' AND disabled_reason IS NULL AND " + SEEDED)
                .query(String.class).list();
        assertThat(active).containsExactlyInAnyOrder("Spielverlagerung", "Jonathan Wilson (The Guardian)");
    }
}
