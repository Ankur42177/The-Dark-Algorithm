package com.pulse.content.contact;

import com.pulse.content.IntegrationTestBase;
import com.pulse.content.auth.AuthProperties;
import com.pulse.content.mail.CapturingMailer;
import com.pulse.content.mail.OutgoingEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contact form: store + notify, validation, honeypot, and the admin inbox. */
class ContactIT extends IntegrationTestBase {

    @Value("${local.server.port}") int port;
    @Autowired CapturingMailer mailer;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired AuthProperties authProps;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        mailer.clear();
    }

    @Test
    void aValidMessageIsStoredAndTheOwnerIsNotifiedWithEscapedHtml() throws Exception {
        String marker = UUID.randomUUID().toString();
        HttpResponse<String> res = post("/api/v1/contact", Map.of("name", "Eve <img src=x>", "email", "Eve@Example.com",
                "message", "Hello " + marker + "\n<script>alert(1)</script>"));

        assertThat(res.statusCode()).isEqualTo(202);
        assertThat(json.readTree(res.body()).get("status").asString()).isEqualTo("received");

        Map<String, Object> row = jdbc.sql("SELECT * FROM contact_messages WHERE message LIKE :m")
                .param("m", "%" + marker + "%").query().singleRow();
        assertThat(row.get("email")).isEqualTo("eve@example.com");
        assertThat(row.get("handled_at")).isNull();
        assertThat(row.get("ip_hash").toString()).hasSize(64).doesNotContain("127.0.0.1");   // HMAC, not the IP

        List<OutgoingEmail> notes = mailer.sentTo("owner@pulse.test");
        assertThat(notes).hasSize(1);
        OutgoingEmail note = notes.getFirst();
        assertThat(note.replyTo()).isEqualTo("eve@example.com");                         // "Reply" answers the visitor
        assertThat(note.html()).contains("&lt;script&gt;").doesNotContain("<script>").doesNotContain("<img");
    }

    @Test
    void invalidInputIsA400AndNothingIsStored() throws Exception {
        long before = count();
        List<Map<String, String>> bad = List.of(
                Map.of("name", "", "email", "a@b.co", "message", "hi"),
                Map.of("name", "Bob", "email", "not-an-email", "message", "hi"),
                Map.of("name", "Bob", "email", "a@b.co", "message", "   "),
                Map.of("name", "x".repeat(101), "email", "a@b.co", "message", "hi"),
                Map.of("name", "Bob", "email", "a@b.co", "message", "y".repeat(5001)),
                Map.of("name", "Bob\r\nBcc: victim@x.com", "email", "a@b.co", "message", "hi"));
        for (Map<String, String> body : bad) {
            HttpResponse<String> res = post("/api/v1/contact", body);
            assertThat(res.statusCode()).as(body.keySet() + " " + body.get("name")).isEqualTo(400);
            assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        }
        assertThat(count()).isEqualTo(before);
        assertThat(mailer.all()).isEmpty();
    }

    @Test
    void honeypotSubmissionsAreAcceptedButIgnored() throws Exception {
        long before = count();
        HttpResponse<String> res = post("/api/v1/contact",
                Map.of("name", "Bot", "email", "bot@spam.example", "message", "Buy now", "website", "http://spam.example"));

        assertThat(res.statusCode()).isEqualTo(202);
        assertThat(count()).isEqualTo(before);
        assertThat(mailer.all()).isEmpty();
    }

    @Test
    void adminCanListAndMarkMessagesHandled() throws Exception {
        String marker = UUID.randomUUID().toString();
        post("/api/v1/contact", Map.of("name", "Grace", "email", "grace@pulse.test", "message", "Admin test " + marker));
        UUID id = jdbc.sql("SELECT id FROM contact_messages WHERE message = :m").param("m", "Admin test " + marker)
                .query(UUID.class).single();

        assertThat(send("GET", "/api/v1/admin/contact", null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> list = send("GET", "/api/v1/admin/contact", null, adminJwt());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(marker).contains("grace@pulse.test");

        HttpResponse<String> handled = send("POST", "/api/v1/admin/contact/" + id + "/handled", "", adminJwt());
        assertThat(handled.statusCode()).isEqualTo(200);
        JsonNode first = json.readTree(handled.body());
        assertThat(first.get("handledAt").isNull()).isFalse();
        // idempotent: the first handled time is kept
        JsonNode second = json.readTree(send("POST", "/api/v1/admin/contact/" + id + "/handled", "", adminJwt()).body());
        assertThat(second.get("handledAt").asString()).isEqualTo(first.get("handledAt").asString());

        assertThat(send("POST", "/api/v1/admin/contact/" + UUID.randomUUID() + "/handled", "", adminJwt()).statusCode()).isEqualTo(404);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM contact_messages").query(Long.class).single();
    }

    private HttpResponse<String> post(String path, Map<String, String> body) throws Exception {
        return send("POST", path, json.writeValueAsString(body), null);
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String adminJwt() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(authProps.issuer()).subject("ankur")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of("ADMIN")).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
