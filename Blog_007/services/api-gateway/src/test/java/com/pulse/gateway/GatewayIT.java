package com.pulse.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 end-to-end tests: the REAL gateway (Netty on a random port) + REAL Redis (Testcontainers)
 * in front of a tiny stub "content-service" built on the JDK's own HTTP server. The stub records what it
 * received, so we can prove which headers the gateway forwards.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);

    static final RSAKey KEY;            // the "content-service" signing key (test only)
    static final HttpServer STUB;       // the fake downstream service
    static final Map<String, Map<String, List<String>>> RECEIVED = new ConcurrentHashMap<>();
    static final AtomicInteger FLAKY_HITS = new AtomicInteger();
    static final int DEAD_PORT;         // nothing listens here: "ingestion/assistant are down"

    static {
        try {
            REDIS.start();
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/", GatewayIT::stubHandler);
            STUB.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());   // handle requests concurrently
            STUB.start();
            try (ServerSocket s = new ServerSocket(0)) {
                DEAD_PORT = s.getLocalPort();
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String stub = "http://127.0.0.1:" + STUB.getAddress().getPort();
        r.add("pulse.gateway.content-uri", () -> stub);
        r.add("pulse.gateway.ingestion-uri", () -> "http://127.0.0.1:" + DEAD_PORT);
        r.add("pulse.gateway.assistant-uri", () -> "http://127.0.0.1:" + DEAD_PORT);
        r.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "1s");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        RECEIVED.clear();
        FLAKY_HITS.set(0);
    }

    // ------------------------------------------------------------------ routing + headers

    @Test
    void routesPublicReadsAndKeepsDownstreamCacheHeaders() throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/posts?limit=3", Map.of(), null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"items\"");
        assertThat(res.headers().firstValue("Cache-Control")).contains("max-age=30, public");   // not overwritten
        String id = res.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(RECEIVED.get("/api/v1/posts").get("X-request-id")).containsExactly(id);        // propagated
        assertThat(res.headers().firstValue("X-RateLimit-Remaining")).contains("59");
    }

    @Test
    void keepsTheClientsRequestIdButDropsSpoofedForwardedFor() throws Exception {
        send("GET", "/api/v1/posts", Map.of("X-Request-Id", "client-req-12345", "X-Forwarded-For", "6.6.6.6"), null);

        Map<String, List<String>> got = RECEIVED.get("/api/v1/posts");
        assertThat(got.get("X-request-id")).containsExactly("client-req-12345");
        assertThat(String.join(",", got.getOrDefault("X-forwarded-for", List.of()))).doesNotContain("6.6.6.6").isNotBlank();
    }

    @Test
    void flakyGetsAreRetriedOnce() throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/posts/flaky", Map.of(), null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(FLAKY_HITS.get()).isEqualTo(2);        // 503 first, retried, then 200
    }

    // ------------------------------------------------------------------ rate limiting

    @Test
    void loginIsLimitedToFivePerFifteenMinutes() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send("POST", "/api/v1/auth/login", Map.of("Content-Type", "application/json"), "{}").statusCode()).isEqualTo(200);
        }
        HttpResponse<String> sixth = send("POST", "/api/v1/auth/login", Map.of("Content-Type", "application/json"), "{}");

        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(sixth.headers().firstValue("Retry-After")).contains("180");
        assertThat(sixth.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(sixth.body()).contains("\"status\":429").contains("login");
        // a different bucket: public reads still work for the same IP
        assertThat(send("GET", "/api/v1/posts", Map.of(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void publicFormsShareAStrictFivePerTenMinutesBucket() throws Exception {
        Map<String, String> json = Map.of("Content-Type", "application/json");
        // newsletter subscribe AND contact count against the same "forms" bucket for this IP
        for (String path : java.util.List.of("/api/v1/newsletter/subscribe", "/api/v1/contact",
                "/api/v1/newsletter/subscribe", "/api/v1/contact", "/api/v1/contact")) {
            assertThat(send("POST", path, json, "{}").statusCode()).as(path).isEqualTo(200);
        }
        assertThat(RECEIVED).containsKeys("/api/v1/newsletter/subscribe", "/api/v1/contact");   // routed to content-service

        HttpResponse<String> sixth = send("POST", "/api/v1/newsletter/subscribe", json, "{}");
        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(sixth.headers().firstValue("Retry-After")).contains("120");
        assertThat(sixth.body()).contains("forms");
        // public reads are a different bucket
        assertThat(send("GET", "/api/v1/posts", Map.of(), null).statusCode()).isEqualTo(200);
        // ... and so are the links clicked from an email: an exhausted forms bucket never blocks an unsubscribe
        assertThat(send("POST", "/api/v1/newsletter/unsubscribe", json, "{}").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/newsletter/confirm", json, "{}").statusCode()).isEqualTo(200);
        assertThat(RECEIVED).containsKeys("/api/v1/newsletter/unsubscribe", "/api/v1/newsletter/confirm");
    }

    @Test
    void adminNewsletterAndContactNeedAnAdminTokenAndRouteToContentService() throws Exception {
        assertThat(send("GET", "/api/v1/admin/newsletter", Map.of(), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/admin/contact", Map.of(), null).statusCode()).isEqualTo(401);

        Map<String, String> admin = Map.of("Authorization", "Bearer " + token(List.of("ADMIN"), KEY));
        assertThat(send("GET", "/api/v1/admin/newsletter", admin, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/admin/newsletter/send-monthly", admin, "").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/admin/contact", admin, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/admin/contact/abc/handled", admin, "").statusCode()).isEqualTo(200);
        assertThat(RECEIVED).containsKeys("/api/v1/admin/newsletter", "/api/v1/admin/newsletter/send-monthly",
                "/api/v1/admin/contact", "/api/v1/admin/contact/abc/handled");
    }

    // ------------------------------------------------------------------ JWT at the edge

    @Test
    void adminWithoutTokenIs401ProblemDetails() throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/admin/posts", Map.of(), null);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"status\":401").contains("requestId");
        assertThat(RECEIVED).doesNotContainKey("/api/v1/admin/posts");          // never reached the service
    }

    @Test
    void adminCookieTokenIsVerifiedAndCookiesAndCsrfPassThroughUntouched() throws Exception {
        String jwt = token(List.of("ADMIN"), KEY);
        HttpResponse<String> res = send("PUT", "/api/v1/admin/posts/123",
                Map.of("Cookie", "pulse_at=" + jwt + "; XSRF-TOKEN=abc", "X-XSRF-TOKEN", "abc", "Content-Type", "application/json"), "{}");

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, List<String>> got = RECEIVED.get("/api/v1/admin/posts/123");
        assertThat(String.join(";", got.get("Cookie"))).contains("pulse_at=").contains("XSRF-TOKEN=abc");
        assertThat(got.get("X-xsrf-token")).containsExactly("abc");
        assertThat(got).doesNotContainKey("Authorization");   // never convert the cookie: content-service must still check CSRF
    }

    @Test
    void wrongRoleIs403AndForgedTokenIs401() throws Exception {
        String reader = token(List.of("READER"), KEY);
        assertThat(send("GET", "/api/v1/admin/posts", Map.of("Authorization", "Bearer " + reader), null).statusCode()).isEqualTo(403);

        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("test-key").generate();   // same kid, different key
        String forged = token(List.of("ADMIN"), attackerKey);
        assertThat(send("GET", "/api/v1/admin/posts", Map.of("Authorization", "Bearer " + forged), null).statusCode()).isEqualTo(401);
    }

    @Test
    void anExpiredOrBogusCookieDoesNotBreakPublicPages() throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/posts", Map.of("Cookie", "pulse_at=not.a.jwt"), null);

        assertThat(res.statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ failures

    @Test
    void downstreamDownGivesA503QuicklyNotAHang() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> res = send("POST", "/api/v1/assistant/chat", Map.of("Content-Type", "application/json"), "{}");
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.body()).contains("\"status\":503").contains("requestId");
        assertThat(ms).isLessThan(1500);
    }

    @Test
    void slowDownstreamGivesA504() throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/sections", Map.of(), null);   // stub sleeps 2 s, timeout 1 s

        assertThat(res.statusCode()).isEqualTo(504);
        assertThat(res.body()).contains("\"status\":504");
    }

    @Test
    void oversizedBodiesAreRejectedBeforeReachingServices() throws Exception {
        String big = "x".repeat(3 * 1024 * 1024);
        HttpResponse<String> res = send("POST", "/api/v1/admin/preview",
                Map.of("Authorization", "Bearer " + token(List.of("ADMIN"), KEY), "Content-Type", "application/json",
                        "Content-Length", String.valueOf(big.length())), big);

        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(RECEIVED).doesNotContainKey("/api/v1/admin/preview");
    }

    // ------------------------------------------------------------------ CORS

    @Test
    void corsPreflightAllowsTheWebsiteWithCredentialsOnly() throws Exception {
        HttpResponse<String> ok = send("OPTIONS", "/api/v1/admin/posts/1", Map.of("Origin", "http://localhost:3000",
                "Access-Control-Request-Method", "PUT", "Access-Control-Request-Headers", "content-type,x-xsrf-token"), null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(ok.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");

        HttpResponse<String> evil = send("OPTIONS", "/api/v1/admin/posts/1", Map.of("Origin", "https://evil.example",
                "Access-Control-Request-Method", "PUT"), null);
        assertThat(evil.statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> send(String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach((k, v) -> {
            if (!k.equalsIgnoreCase("Content-Length")) {
                b.header(k, v);
            }
        });
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> roles, RSAKey key) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://pulse.local").subject("ankur").claim("roles", roles)
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 600_000)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** The fake downstream: records request headers per path and answers like content-service would. */
    private static void stubHandler(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        RECEIVED.put(path, Map.copyOf(ex.getRequestHeaders()));
        ex.getRequestBody().readAllBytes();
        int status = 200;
        String body = "{\"items\":[],\"nextCursor\":null}";
        switch (path) {
            case "/.well-known/jwks.json" -> body = new JWKSet(KEY.toPublicJWK()).toString();
            case "/api/v1/posts/flaky" -> status = FLAKY_HITS.incrementAndGet() == 1 ? 503 : 200;
            case "/api/v1/sections" -> sleep(2000);
            default -> { }
        }
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "max-age=30, public");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
