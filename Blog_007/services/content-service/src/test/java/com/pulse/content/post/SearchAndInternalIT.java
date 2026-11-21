package com.pulse.content.post;

import com.pulse.content.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Full-text search + the internal API used by the AI assistant (Phase 8). */
class SearchAndInternalIT extends IntegrationTestBase {

    @Value("${local.server.port}")
    int port;

    private RestTestClient http() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void searchRanksTitleMatchesAndHidesDrafts() {
        http().get().uri("/api/v1/search?q=keyset pagination").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].slug").isEqualTo("why-keyset-pagination-beats-offset");
        // "embeddings" only appears in the DRAFT post → nothing found
        http().get().uri("/api/v1/search?q=embeddings").exchange().expectStatus().isOk().expectBody().json("[]");
        http().get().uri("/api/v1/search?q=agents&section=FOOTBALL").exchange().expectBody().json("[]");
    }

    @Test
    void searchValidatesInput() {
        http().get().uri("/api/v1/search?q=").exchange().expectStatus().isBadRequest();
        http().get().uri("/api/v1/search?q=x&limit=500").exchange().expectStatus().isBadRequest();
        // websearch_to_tsquery never throws on odd input, so even symbol soup is a 200
        http().get().uri("/api/v1/search?q=\"(((-|||").exchange().expectStatus().isOk();
    }

    @Test
    void internalRecentPostsFiltersBySectionAndAge() {
        http().get().uri("/internal/v1/posts?section=AI&sinceDays=7").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2);
        http().get().uri("/internal/v1/posts?section=FOOTBALL&sinceDays=3").exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(1);       // only the 2-day-old Arsenal post
        http().get().uri("/internal/v1/posts/rag-in-one-picture").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.bodyHtml").exists();
    }
}
