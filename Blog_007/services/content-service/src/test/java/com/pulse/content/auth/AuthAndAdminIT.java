package com.pulse.content.auth;

import com.pulse.content.IntegrationTestBase;
import com.pulse.content.admin.ScheduledPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 4 end-to-end security tests over real HTTP with real cookies:
 * login, throttling, JWT + CSRF on admin writes, refresh rotation + reuse detection,
 * publish flow + cache eviction, optimistic locking, XSS sanitizing, idempotency, scheduling, JWKS.
 */
class AuthAndAdminIT extends IntegrationTestBase {

    static final String PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void admin(DynamicPropertyRegistry r) {
        r.add("pulse.auth.admin-password-hash", () -> PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        r.add("pulse.auth.cookie-secure", () -> "false");
    }

    @Value("${local.server.port}") int port;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient jdbc;
    @Autowired ScheduledPublisher scheduledPublisher;
    @Autowired JsonMapper json;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetThrottle() {
        redis.delete(redis.keys("auth:fail:*"));
    }

    // ------------------------------------------------------------------ login

    @Test
    void wrongPasswordIs401AndTheSixthAttemptIsThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(new Session().login("wrong password!").statusCode()).isEqualTo(401);
        }
        HttpResponse<String> sixth = new Session().login(PASSWORD);   // even the RIGHT password is blocked now
        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(sixth.headers().firstValue("Retry-After")).contains("900");
    }

    @Test
    void loginSetsHardenedCookiesAndMeWorks() throws Exception {
        Session s = new Session();
        HttpResponse<String> res = s.login(PASSWORD);
        assertThat(res.statusCode()).isEqualTo(200);
        String setCookies = String.join("\n", res.headers().allValues("Set-Cookie"));
        assertThat(setCookies).contains("pulse_at=").contains("pulse_rt=").contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");                         // refresh cookie only goes to /auth
        assertThat(s.get("/api/v1/auth/me").body()).contains("\"displayName\":\"Ankur Kumar\"");
    }

    // ------------------------------------------------------------------ authorization + CSRF

    @Test
    void adminApiNeedsAJwtAndWritesNeedACsrfToken() throws Exception {
        assertThat(new Session().get("/api/v1/admin/posts").statusCode()).isEqualTo(401);   // anonymous

        Session s = new Session();
        s.login(PASSWORD);
        assertThat(s.get("/api/v1/admin/posts").statusCode()).isEqualTo(200);              // reads: JWT is enough
        assertThat(s.send("POST", "/api/v1/admin/posts", draft("No csrf"), false).statusCode()).isEqualTo(403);
        assertThat(s.send("POST", "/api/v1/admin/posts", draft("With csrf"), true).statusCode()).isEqualTo(201);
    }

    @Test
    void tamperedJwtIsRejected() throws Exception {
        Session s = new Session();
        s.login(PASSWORD);
        String jwt = s.cookies.get("pulse_at");
        String[] parts = jwt.split("\\.");
        String evilPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"attacker\",\"roles\":[\"ADMIN\"],\"iss\":\"https://pulse.local\",\"exp\":9999999999}".getBytes());
        s.cookies.put("pulse_at", parts[0] + "." + evilPayload + "." + parts[2]);          // signature no longer matches
        assertThat(s.get("/api/v1/admin/posts").statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ refresh rotation

    @Test
    void refreshRotatesAndReusingAnOldTokenRevokesTheWholeFamily() throws Exception {
        Session s = new Session();
        s.login(PASSWORD);
        String rt1 = s.cookies.get("pulse_rt");

        assertThat(s.post("/api/v1/auth/refresh").statusCode()).isEqualTo(200);
        String rt2 = s.cookies.get("pulse_rt");
        assertThat(rt2).isNotEqualTo(rt1);

        Session attacker = new Session();                                // replays the STOLEN, already-used rt1
        attacker.cookies.put("pulse_rt", rt1);
        HttpResponse<String> replay = attacker.post("/api/v1/auth/refresh");
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(replay.body()).contains("reuse detected");

        assertThat(s.post("/api/v1/auth/refresh").statusCode()).isEqualTo(401);   // the real user's rt2 is dead too
    }

    // ------------------------------------------------------------------ publishing

    @Test
    void draftIsHiddenPublishMakesItVisibleAndStaleEditsConflict() throws Exception {
        Session s = new Session();
        s.login(PASSWORD);
        JsonNode created = json.readTree(s.send("POST", "/api/v1/admin/posts", draft("Phase 4 publish flow"), true).body());
        String id = created.get("id").asString();
        String slug = created.get("slug").asString();
        assertThat(slug).isEqualTo("phase-4-publish-flow");

        assertThat(new Session().get("/api/v1/posts/" + slug).statusCode()).isEqualTo(404);   // drafts are private
        assertThat(s.send("POST", "/api/v1/admin/posts/" + id + "/publish", "", true).statusCode()).isEqualTo(200);
        assertThat(new Session().get("/api/v1/posts/" + slug).statusCode()).isEqualTo(200);   // cache evicted, visible

        String stale = draft("Edited title", 0L);                       // version 0, but publish bumped it
        HttpResponse<String> conflict = s.send("PUT", "/api/v1/admin/posts/" + id, stale, true);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("changed by someone else");

        long version = json.readTree(s.get("/api/v1/admin/posts/" + id).body()).get("version").asLong();
        assertThat(s.send("PUT", "/api/v1/admin/posts/" + id, draft("Edited title", version), true).statusCode()).isEqualTo(200);
        // Phase 6: eviction is now event-driven (outbox → Kafka → content-cache-sync) → eventually consistent.
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(new Session().get("/api/v1/posts/" + slug).body()).contains("Edited title"));

        assertThat(s.send("POST", "/api/v1/admin/posts/" + id + "/unpublish", "", true).statusCode()).isEqualTo(200);
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(new Session().get("/api/v1/posts/" + slug).statusCode()).isEqualTo(404));
    }

    @Test
    void markdownIsRenderedAndXssIsStripped() throws Exception {
        Session s = new Session();
        s.login(PASSWORD);
        String body = """
                # Hello
                <script>alert('xss')</script>
                [click me](javascript:alert(1))
                <img src=x onerror=alert(1)>
                **bold** and `code`
                """;
        String req = json.writeValueAsString(Map.of("title", "XSS attempt", "bodyMd", body, "section", "TECH"));
        JsonNode post = json.readTree(s.send("POST", "/api/v1/admin/posts", req, true).body());
        String html = post.get("bodyHtml").asString();

        assertThat(html).contains("<h1>Hello</h1>").contains("<strong>bold</strong>").contains("<code>code</code>");
        assertThat(html.toLowerCase()).doesNotContain("<script").doesNotContain("javascript:").doesNotContain("onerror=").doesNotContain("<img");
    }

    @Test
    void sameIdempotencyKeyCreatesOnlyOnePost() throws Exception {
        Session s = new Session();
        s.login(PASSWORD);
        String key = UUID.randomUUID().toString();
        HttpResponse<String> first = s.sendWithHeader("POST", "/api/v1/admin/posts", draft("Idempotent post"), "Idempotency-Key", key);
        HttpResponse<String> retry = s.sendWithHeader("POST", "/api/v1/admin/posts", draft("Idempotent post"), "Idempotency-Key", key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(retry.body()).get("id")).isEqualTo(json.readTree(first.body()).get("id"));
        assertThat(jdbc.sql("SELECT count(*) FROM posts WHERE title = 'Idempotent post'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void scheduledPostsArePublishedWhenTheirTimeComes() throws Exception {
        Session s = new Session();
        s.login(PASSWORD);
        JsonNode created = json.readTree(s.send("POST", "/api/v1/admin/posts", draft("Scheduled post"), true).body());
        String id = created.get("id").asString();
        String at = java.time.Instant.now().plusSeconds(3600).toString();
        assertThat(s.send("POST", "/api/v1/admin/posts/" + id + "/schedule", "{\"publishAt\":\"" + at + "\"}", true).statusCode()).isEqualTo(200);
        assertThat(scheduledPublisher.publishDuePosts()).doesNotContain("scheduled-post");          // not yet

        jdbc.sql("UPDATE posts SET published_at = now() - interval '1 minute' WHERE id = :id").param("id", UUID.fromString(id)).update();
        assertThat(scheduledPublisher.publishDuePosts()).containsExactly("scheduled-post");
        assertThat(new Session().get("/api/v1/posts/scheduled-post").statusCode()).isEqualTo(200);
    }

    @Test
    void jwksPublishesOnlyThePublicKey() throws Exception {
        JsonNode jwks = json.readTree(new Session().get("/.well-known/jwks.json").body());
        JsonNode key = jwks.get("keys").get(0);
        assertThat(key.get("kty").asString()).isEqualTo("RSA");
        assertThat(key.has("kid")).isTrue();
        assertThat(key.has("d")).isFalse();             // "d" = the private exponent: must never be published
    }

    // ------------------------------------------------------------------ tiny cookie-aware HTTP client

    private String draft(String title) {
        return draft(title, null);
    }

    private String draft(String title, Long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("bodyMd", "Some **markdown** body about " + title);
        m.put("section", "TECH");
        m.put("tags", java.util.List.of("phase4"));
        if (version != null) {
            m.put("version", version);
        }
        return json.writeValueAsString(m);
    }

    /** Remembers cookies like a browser and can echo the XSRF-TOKEN cookie as the X-XSRF-TOKEN header. */
    class Session {
        final Map<String, String> cookies = new LinkedHashMap<>();

        HttpResponse<String> login(String password) throws Exception {
            return send("POST", "/api/v1/auth/login", "{\"username\":\"ankur\",\"password\":\"" + password + "\"}", false);
        }

        HttpResponse<String> get(String path) throws Exception {
            return exchange(base(path).GET());
        }

        HttpResponse<String> post(String path) throws Exception {
            return send("POST", path, "", false);
        }

        HttpResponse<String> send(String method, String path, String body, boolean withCsrf) throws Exception {
            HttpRequest.Builder b = base(path).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (withCsrf && cookies.containsKey("XSRF-TOKEN")) {
                b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            return exchange(b);
        }

        HttpResponse<String> sendWithHeader(String method, String path, String body, String header, String value) throws Exception {
            HttpRequest.Builder b = base(path).header("Content-Type", "application/json").header(header, value)
                    .header("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", ""))
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            return exchange(b);
        }

        private HttpRequest.Builder base(String path) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (!cookies.isEmpty()) {
                b.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
            }
            return b;
        }

        private HttpResponse<String> exchange(HttpRequest.Builder b) throws Exception {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String sc : res.headers().allValues("Set-Cookie")) {
                String pair = sc.split(";", 2)[0];
                String name = pair.substring(0, pair.indexOf('='));
                String value = pair.substring(pair.indexOf('=') + 1);
                if (value.isEmpty()) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
            return res;
        }
    }
}
