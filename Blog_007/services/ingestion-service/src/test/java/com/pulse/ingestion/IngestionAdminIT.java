package com.pulse.ingestion;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Admin API over real HTTP (PLAN §12.2): who may call it (JWT + ROLE_ADMIN), CSRF on writes,
 * SSRF-safe validation of new feed URLs, and the DLQ requeue flow.
 */
class IngestionAdminIT extends IngestionTestBase {

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void isolate() {
        jdbc.sql("UPDATE feed_sources SET active = false").update();         // the scheduler must never pick real feeds
        WEB.resetAll();
        stubJwks();
        WEB.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
    }

    // ------------------------------------------------------------------ who may call

    @Test
    void anonymousIs401AndNonAdminIs403() throws Exception {
        assertThat(send(request("/sources", null).GET()).statusCode()).isEqualTo(401);
        assertThat(send(request("/sources", token(List.of("EDITOR"), 300))).statusCode()).isEqualTo(403);
        assertThat(send(request("/sources", token(List.of("ADMIN"), -300))).statusCode())      // expired (beyond the 60 s clock skew)
                .as("an expired token is rejected").isEqualTo(401);
        assertThat(send(request("/sources", admin())).statusCode()).isEqualTo(200);
    }

    @Test
    void tokenFromTheBrowserCookieWorksToo() throws Exception {
        var res = send(HttpRequest.newBuilder(url("/sources")).header("Cookie", "pulse_at=" + admin()).GET());
        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    void cookieAuthenticatedWritesNeedCsrf() throws Exception {
        String body = newSourceJson("http://localhost:" + WEB.port() + "/x.xml?" + UUID.randomUUID());
        var noCsrf = send(HttpRequest.newBuilder(url("/sources")).header("Cookie", "pulse_at=" + admin())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(noCsrf.statusCode()).as("browser cookie + no CSRF pair = what a forged cross-site form looks like").isEqualTo(403);

        String csrf = UUID.randomUUID().toString();
        var wrongCsrf = send(HttpRequest.newBuilder(url("/sources")).header("Cookie", "pulse_at=" + admin() + "; XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", "guessed").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(wrongCsrf.statusCode()).isEqualTo(403);
    }

    /**
     * Spring Security skips CSRF when the token arrives in an Authorization header: a browser never attaches that
     * header on its own, so a cross-site request can't carry it (CSRF needs AMBIENT credentials, like cookies).
     */
    @Test
    void bearerHeaderWritesDoNotNeedCsrf() throws Exception {
        var res = send(request("/sources", admin()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(newSourceJson("http://localhost:" + WEB.port() + "/y.xml?" + UUID.randomUUID()))));
        assertThat(res.statusCode()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ sources CRUD + validation

    @Test
    void createPatchAndFetchNow() throws Exception {
        String feedUrl = "http://localhost:" + WEB.port() + "/admin-feed.xml?" + UUID.randomUUID();
        WEB.stubFor(get(urlPathEqualTo("/admin-feed.xml")).willReturn(aResponse().withStatus(304)));

        var created = send(write("/sources", "POST", newSourceJson(feedUrl)));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        UUID id = UUID.fromString(body.get("id").asString());
        assertThat(body.get("section").asString()).isEqualTo("AI");

        assertThat(send(write("/sources", "POST", newSourceJson(feedUrl))).statusCode())
                .as("feed_url is UNIQUE → 409 Conflict").isEqualTo(409);

        var patched = send(write("/sources/" + id, "PATCH", "{\"active\":false,\"fetchIntervalMin\":60}"));
        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(json.readTree(patched.body()).get("fetchIntervalMin").asInt()).isEqualTo(60);

        assertThat(send(write("/sources/" + id, "PATCH", "{\"fetchIntervalMin\":1}")).statusCode())
                .as("polite minimum is 15 minutes").isEqualTo(400);

        assertThat(send(write("/sources/" + id + "/fetch-now", "POST", "")).statusCode())
                .as("paused source: the worker would skip it, so say so instead of 202").isEqualTo(409);
        assertThat(send(write("/sources/" + id, "PATCH", "{\"active\":true}")).statusCode()).isEqualTo(200);

        var queued = send(write("/sources/" + id + "/fetch-now", "POST", ""));
        assertThat(queued.statusCode()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM fetch_runs WHERE source_id = :id").param("id", id).query(Long.class).single() == 1);

        var runs = json.readTree(send(request("/ingestion/runs", admin())).body());
        assertThat(runs.isArray()).isTrue();
        assertThat(runs.toString()).contains(id.toString());

        assertThat(send(write("/sources/" + UUID.randomUUID() + "/fetch-now", "POST", "")).statusCode()).isEqualTo(404);
    }

    @Test
    void dangerousFeedUrlsAreRefusedBeforeSaving() throws Exception {
        for (String bad : List.of("file:///etc/passwd", "gopher://evil.example/", "http://user:pw@evil.example/feed",
                "http://internal.example:6379/")) {
            var res = send(write("/sources", "POST", newSourceJson(bad)));
            assertThat(res.statusCode()).as(bad).isEqualTo(400);
            assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("problem+json"));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM feed_sources WHERE feed_url LIKE '%evil%' OR feed_url LIKE 'file:%'")
                .query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ DLQ

    @Test
    void deadLettersAreListedAndCanBeRequeuedExactlyOnce() throws Exception {
        UUID source = jdbc.sql("""
                        INSERT INTO feed_sources (name, site_url, feed_url, section, next_fetch_at, active)
                        VALUES (:n, 'https://fake.example', :u, 'TECH', now() + interval '1 day', true) RETURNING id""")
                .param("n", "DLQ " + UUID.randomUUID()).param("u", "http://localhost:" + WEB.port() + "/dlq.xml?" + UUID.randomUUID())
                .query(UUID.class).single();
        WEB.stubFor(get(urlPathEqualTo("/dlq.xml")).willReturn(aResponse().withStatus(304)));
        UUID dead = jdbc.sql("""
                        INSERT INTO dead_letters (message_id, source_id, attempts, reason, payload)
                        VALUES (gen_random_uuid(), :s, 5, 'HTTP 503', '{}'::jsonb) RETURNING id""")
                .param("s", source).query(UUID.class).single();

        assertThat(send(request("/ingestion/dlq", admin())).body()).contains(dead.toString(), "HTTP 503");

        jdbc.sql("UPDATE feed_sources SET active = false WHERE id = :id").param("id", source).update();
        assertThat(send(write("/ingestion/dlq/" + dead + "/requeue", "POST", "")).statusCode()).as("paused source").isEqualTo(409);
        jdbc.sql("UPDATE feed_sources SET active = true WHERE id = :id").param("id", source).update();

        assertThat(send(write("/ingestion/dlq/" + dead + "/requeue", "POST", "")).statusCode()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM fetch_runs WHERE source_id = :id").param("id", source).query(Long.class).single() == 1);

        assertThat(send(write("/ingestion/dlq/" + dead + "/requeue", "POST", "")).statusCode())
                .as("a double-click must not enqueue the job twice").isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private String newSourceJson(String feedUrl) {
        return """
                {"name":"Admin test %s","siteUrl":"https://fake.example","feedUrl":"%s","section":"AI","fetchIntervalMin":30}"""
                .formatted(UUID.randomUUID(), feedUrl);
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + "/api/v1/admin" + path);
    }

    private HttpRequest.Builder request(String path, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url(path));
        return bearer == null ? b : b.header("Authorization", "Bearer " + bearer);
    }

    /**
     * A write as the SPA sends it: the JWT in the HttpOnly pulse_at cookie, plus the CSRF double-submit pair — the XSRF-TOKEN cookie and the same value in
     * the X-XSRF-TOKEN header. A malicious site can make the browser SEND the cookie, but it cannot READ it, so it
     * cannot copy it into the header.
     */
    private HttpRequest.Builder write(String path, String method, String body) throws Exception {
        String csrf = UUID.randomUUID().toString();
        return HttpRequest.newBuilder(url(path))
                .header("Content-Type", "application/json")
                .header("Cookie", "pulse_at=" + admin() + "; XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    }

    private String admin() throws Exception {
        return token(List.of("ADMIN"), 300);
    }

    /** Sign a token the way content-service does (RS256, issuer, roles claim) with the test key. */
    private static String token(List<String> roles, long validForSeconds) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("https://pulse.local").subject("admin").claim("roles", roles)
                        .issueTime(Date.from(now.minusSeconds(120))).expirationTime(Date.from(now.plusSeconds(validForSeconds))).build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
