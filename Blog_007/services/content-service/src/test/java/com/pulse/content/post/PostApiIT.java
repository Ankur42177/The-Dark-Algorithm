package com.pulse.content.post;

import com.pulse.content.post.PostDtos.CursorPage;
import com.pulse.content.post.PostDtos.PostSummary;
import com.pulse.content.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION test: starts the whole Spring application against a REAL PostgreSQL running in
 * Docker (Testcontainers), runs the Flyway migrations + dev seed data, and calls the real
 * service layer. If the SQL, indexes or mappings are wrong, this test fails — an in-memory
 * fake database could not tell us that.
 *
 * <p>Name ends in "IT" so Maven's failsafe plugin runs it during {@code mvn verify}.
 * {@code disabledWithoutDocker = true}: skipped (not failed) when Docker isn't running.
 */
class PostApiIT extends IntegrationTestBase {

    @Autowired
    PostService postService;

    @Autowired
    JdbcClient jdbc;

    @Value("${local.server.port}") // the random port Tomcat picked
    int port;

    /** Spring 7's test HTTP client: sends REAL HTTP requests to our running app. */
    private RestTestClient http() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void firstPageIsNewestFirstAndOnlyPublished() {
        CursorPage<PostSummary> page = postService.listPublished(null, null, null, 50);

        assertThat(page.items()).hasSize(8);                         // 9 seeded, 1 is a DRAFT
        assertThat(page.items()).extracting(PostSummary::slug)
                .doesNotContain("draft-embeddings-deep-dive");
        assertThat(page.items()).extracting(PostSummary::publishedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));      // newest first
        assertThat(page.nextCursor()).isNull();                      // everything fit on one page
    }

    @Test
    void walkingAllPagesWithTheCursorReturnsEveryPostExactlyOnce() {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CursorPage<PostSummary> page = postService.listPublished(null, null, cursor, 3);
            page.items().forEach(p -> seen.add(p.slug()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);                               // 3 + 3 + 2
        assertThat(seen).hasSize(8);
        assertThat(new HashSet<>(seen)).hasSize(8);                   // no duplicates, nothing skipped
    }

    @Test
    void sectionAndTagFiltersWork() {
        assertThat(postService.listPublished(Section.FOOTBALL, null, null, 10).items())
                .allMatch(p -> p.section() == Section.FOOTBALL)
                .hasSize(3);

        assertThat(postService.listPublished(null, "llm", null, 10).items())
                .extracting(PostSummary::slug)
                .containsExactlyInAnyOrder("rag-in-one-picture", "what-is-an-ai-agent");
    }

    @Test
    void postDetailIncludesTagsAndHtml() {
        var post = postService.getPublishedBySlug("rag-in-one-picture");

        assertThat(post.tags()).containsExactly("llm", "rag");
        assertThat(post.bodyHtml()).contains("<h1>RAG</h1>");
    }

    @Test
    void httpGetPostReturnsJsonWithCacheHeadersAndEtag() {
        http().get().uri("/api/v1/posts/rag-in-one-picture")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueMatches("Cache-Control", ".*max-age=300.*")
                .expectHeader().exists("ETag")
                .expectBody().jsonPath("$.title").isEqualTo("RAG in one picture");
    }

    @Test
    void unknownSlugIsA404ProblemDetail() {
        http().get().uri("/api/v1/posts/does-not-exist")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Post not found")
                .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void badInputIsA400NotA500() {
        http().get().uri("/api/v1/posts?limit=500").exchange().expectStatus().isBadRequest();
        http().get().uri("/api/v1/posts?cursor=garbage!!").exchange().expectStatus().isBadRequest();
        http().get().uri("/api/v1/posts?section=SPORTS").exchange().expectStatus().isBadRequest();
    }

    @Test
    void sectionsAreCounted() {
        // AI was merged into TECH (V6): only two sections have posts now. TECH >= 5 because other test
        // classes share this database and may publish TECH posts too.
        List<PostDtos.SectionCount> counts = postService.sections();
        assertThat(counts).extracting(PostDtos.SectionCount::section).containsExactly(Section.FOOTBALL, Section.TECH);
        assertThat(counts.get(0).posts()).isEqualTo(3L);
        assertThat(counts.get(1).posts()).isGreaterThanOrEqualTo(5L);
    }

    /**
     * Regression test for a bug found while writing the Phase 0 break-it notes: with no driver
     * timeouts, a frozen database made requests hang forever. Every app DB session must carry
     * a server-side statement timeout (configured in application.yml).
     */
    @Test
    void databaseSessionsHaveAStatementTimeout() {
        String timeout = jdbc.sql("SHOW statement_timeout").query(String.class).single();

        assertThat(timeout).isEqualTo("3s");
    }
}
