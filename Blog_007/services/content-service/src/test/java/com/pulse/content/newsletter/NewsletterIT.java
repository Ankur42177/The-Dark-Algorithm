package com.pulse.content.newsletter;

import com.pulse.content.IntegrationTestBase;
import com.pulse.content.auth.AuthProperties;
import com.pulse.content.mail.CapturingMailer;
import com.pulse.content.mail.OutgoingEmail;
import com.pulse.content.newsletter.NewsletterDtos.SendResult;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Newsletter end to end over real HTTP: double opt-in, no email enumeration, honeypot, token expiry,
 * idempotent unsubscribe, and the monthly job's "at most one email per subscriber per month" guarantee.
 * Emails are captured by {@link CapturingMailer} (see IntegrationTestBase).
 */
class NewsletterIT extends IntegrationTestBase {

    private static final Pattern CONFIRM_LINK = Pattern.compile("/newsletter/confirm\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern UNSUB_LINK = Pattern.compile("/newsletter/unsubscribe\\?token=([A-Za-z0-9_-]+)");

    @Value("${local.server.port}") int port;
    @Autowired CapturingMailer mailer;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired NewsletterService newsletter;
    @Autowired NewsletterTokens tokens;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired AuthProperties authProps;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        mailer.clear();
    }

    // ------------------------------------------------------------------ double opt-in

    @Test
    void subscribeThenConfirmActivatesAndSendsAWelcomeArticleThatCountsAsThisMonthsDelivery() throws Exception {
        String email = unique("Reader");
        HttpResponse<String> sub = post("/api/v1/newsletter/subscribe",
                Map.of("email", "  " + email.toUpperCase() + " ", "name", "Ada <b>Lovelace</b>"));

        assertThat(sub.statusCode()).isEqualTo(202);
        assertThat(json.readTree(sub.body()).get("status").asString()).isEqualTo("pending");
        Map<String, Object> row = subscriber(email);                     // stored lower-cased and trimmed
        assertThat(row.get("status")).isEqualTo("PENDING");

        List<OutgoingEmail> confirmMails = mailer.sentTo(email);
        assertThat(confirmMails).hasSize(1);
        String token = extract(CONFIRM_LINK, confirmMails.getFirst().text());
        assertThat(confirmMails.getFirst().text()).contains("http://localhost:3000/newsletter/confirm?token=");
        // never store raw tokens: only SHA-256(token)
        assertThat(row.get("confirm_token_hash").toString()).isEqualTo(NewsletterTokens.sha256Hex(token)).isNotEqualTo(token);
        // the user's name is HTML-escaped in the HTML part
        assertThat(confirmMails.getFirst().html()).contains("Ada &lt;b&gt;Lovelace&lt;/b&gt;").doesNotContain("<b>Lovelace");

        HttpResponse<String> confirm = post("/api/v1/newsletter/confirm", Map.of("token", token));
        assertThat(confirm.statusCode()).isEqualTo(200);
        assertThat(json.readTree(confirm.body()).get("status").asString()).isEqualTo("active");
        assertThat(subscriber(email).get("status")).isEqualTo("ACTIVE");

        List<OutgoingEmail> all = mailer.sentTo(email);
        assertThat(all).hasSize(2);
        OutgoingEmail welcome = all.get(1);
        assertThat(welcome.subject()).isEqualTo("Welcome to Pulse");
        assertThat(welcome.text()).contains("http://localhost:3000/posts/");
        assertThat(welcome.headers().get("List-Unsubscribe")).startsWith("<http://localhost:3000/newsletter/unsubscribe?token=");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM newsletter_deliveries d JOIN newsletter_subscribers s ON s.id = d.subscriber_id
                         WHERE s.email = :e AND d.period = :p AND d.sent_at IS NOT NULL AND d.post_id IS NOT NULL""")
                .param("e", email).param("p", NewsletterService.currentPeriod()).query(Long.class).single()).isEqualTo(1);

        // a second click on the same link: still 200, but no second welcome email
        assertThat(post("/api/v1/newsletter/confirm", Map.of("token", token)).statusCode()).isEqualTo(200);
        assertThat(mailer.sentTo(email)).hasSize(2);
    }

    @Test
    void subscribeAnswersTheSameForNewPendingAndActiveAddresses() throws Exception {
        String email = unique("enum");
        HttpResponse<String> first = post("/api/v1/newsletter/subscribe", Map.of("email", email));
        confirm(email);
        mailer.clear();

        HttpResponse<String> again = post("/api/v1/newsletter/subscribe", Map.of("email", email));   // already ACTIVE
        assertThat(again.statusCode()).isEqualTo(first.statusCode()).isEqualTo(202);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(mailer.all()).isEmpty();                              // nothing sent for an active subscriber

        String pending = unique("pending");
        post("/api/v1/newsletter/subscribe", Map.of("email", pending));
        HttpResponse<String> retry = post("/api/v1/newsletter/subscribe", Map.of("email", pending));
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(mailer.sentTo(pending)).hasSize(1);                   // resend cooldown: no "email bombing"

        // after the cooldown, a pending address gets a NEW link and the old one stops working
        String oldToken = extract(CONFIRM_LINK, mailer.sentTo(pending).getFirst().text());
        jdbc.sql("UPDATE newsletter_subscribers SET confirm_sent_at = now() - interval '1 hour' WHERE email = :e")
                .param("e", pending).update();
        post("/api/v1/newsletter/subscribe", Map.of("email", pending));
        assertThat(mailer.sentTo(pending)).hasSize(2);
        assertThat(post("/api/v1/newsletter/confirm", Map.of("token", oldToken)).statusCode()).isEqualTo(404);
    }

    @Test
    void honeypotSubmissionsAreAcceptedButIgnored() throws Exception {
        String email = unique("bot");
        HttpResponse<String> res = post("/api/v1/newsletter/subscribe", Map.of("email", email, "website", "http://spam.example"));

        assertThat(res.statusCode()).isEqualTo(202);
        assertThat(json.readTree(res.body()).get("status").asString()).isEqualTo("pending");
        assertThat(count("SELECT count(*) FROM newsletter_subscribers WHERE email = '" + email + "'")).isZero();
        assertThat(mailer.all()).isEmpty();
    }

    @Test
    void invalidInputIsA400ProblemDetail() throws Exception {
        for (Map<String, String> body : List.of(Map.of("email", "not-an-email"), Map.of("email", ""),
                Map.of("email", "a@b.co\r\nBcc: victim@x.com"), Map.of("email", unique("x"), "name", "line\nbreak"),
                // "address smuggling": JavaMail would deliver this to victim@example.com while our cooldown sees a
                // different row per variant (a1<…>, a2<…>, …) → now rejected by the stricter EMAIL rule
                Map.of("email", "a1<victim@example.com>"), Map.of("email", "\"x\"@example.com"),
                Map.of("email", "a@b.co,c@d.co"))) {
            HttpResponse<String> res = post("/api/v1/newsletter/subscribe", body);
            assertThat(res.statusCode()).as(body.toString()).isEqualTo(400);
            assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        }
        assertThat(post("/api/v1/newsletter/confirm", Map.of()).statusCode()).isEqualTo(400);
        assertThat(mailer.all()).isEmpty();
    }

    @Test
    void unknownOrExpiredConfirmTokensAre404() throws Exception {
        HttpResponse<String> unknown = post("/api/v1/newsletter/confirm", Map.of("token", "definitely-not-a-real-token-1234567890"));
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("invalid-token");

        String email = unique("expired");
        post("/api/v1/newsletter/subscribe", Map.of("email", email));
        String token = extract(CONFIRM_LINK, mailer.sentTo(email).getFirst().text());
        jdbc.sql("UPDATE newsletter_subscribers SET confirm_expires_at = now() - interval '1 minute' WHERE email = :e")
                .param("e", email).update();

        assertThat(post("/api/v1/newsletter/confirm", Map.of("token", token)).statusCode()).isEqualTo(404);
        assertThat(subscriber(email).get("status")).isEqualTo("PENDING");
    }

    @Test
    void unsubscribeIsIdempotentAndUnknownTokensAre404() throws Exception {
        String email = unique("leaver");
        post("/api/v1/newsletter/subscribe", Map.of("email", email));
        confirm(email);
        String unsub = extract(UNSUB_LINK, mailer.sentTo(email).getLast().text());

        HttpResponse<String> first = post("/api/v1/newsletter/unsubscribe", Map.of("token", unsub));
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).get("status").asString()).isEqualTo("unsubscribed");
        assertThat(post("/api/v1/newsletter/unsubscribe", Map.of("token", unsub)).statusCode()).isEqualTo(200);
        assertThat(subscriber(email).get("status")).isEqualTo("UNSUBSCRIBED");

        assertThat(post("/api/v1/newsletter/unsubscribe", Map.of("token", "nope-nope-nope-nope-nope")).statusCode()).isEqualTo(404);

        // unsubscribed people are not mailed by the monthly job
        mailer.clear();
        newsletter.sendMonthly(YearMonth.of(2041, 1));
        assertThat(mailer.sentTo(email)).isEmpty();
    }

    @Test
    void resubscribingRightAfterUnsubscribingSendsANewConfirmEmail() throws Exception {
        // Bug found in the Phase 7 review: confirm_sent_at survived the unsubscribe, so re-subscribing within the
        // 10-minute cooldown answered 202 "pending" but sent nothing and left the row UNSUBSCRIBED.
        String email = unique("comeback");
        post("/api/v1/newsletter/subscribe", Map.of("email", email));
        confirm(email);
        String unsub = extract(UNSUB_LINK, mailer.sentTo(email).getLast().text());
        assertThat(post("/api/v1/newsletter/unsubscribe", Map.of("token", unsub)).statusCode()).isEqualTo(200);
        mailer.clear();

        assertThat(post("/api/v1/newsletter/subscribe", Map.of("email", email)).statusCode()).isEqualTo(202);

        assertThat(subscriber(email).get("status")).isEqualTo("PENDING");
        assertThat(mailer.sentTo(email)).hasSize(1);
        assertThat(mailer.sentTo(email).getFirst().subject()).isEqualTo("Confirm your Pulse subscription");
        // ... and the cooldown applies again from here: an immediate second submit sends nothing
        post("/api/v1/newsletter/subscribe", Map.of("email", email));
        assertThat(mailer.sentTo(email)).hasSize(1);
    }

    // ------------------------------------------------------------------ monthly job

    @Test
    void monthlyJobSendsAtMostOncePerSubscriberAndPeriodEvenWhenRunTwiceInParallel() throws Exception {
        List<String> emails = List.of(unique("m1"), unique("m2"), unique("m3"));
        emails.forEach(this::insertActive);
        YearMonth month = YearMonth.of(2040, 3);

        ExecutorService pool = Executors.newFixedThreadPool(2);       // two "replicas" fire at the same moment
        try {
            Callable<SendResult> job = () -> newsletter.sendMonthly(month);
            List<Future<SendResult>> runs = pool.invokeAll(List.of(job, job));
            for (Future<SendResult> r : runs) {
                r.get();
            }
        } finally {
            pool.shutdown();
        }
        SendResult third = newsletter.sendMonthly(month);              // and the admin presses the button again

        for (String e : emails) {
            assertThat(mailer.sentTo(e)).as(e).hasSize(1);
            assertThat(mailer.sentTo(e).getFirst().headers()).containsKey("List-Unsubscribe");
            assertThat(count("""
                    SELECT count(*) FROM newsletter_deliveries d JOIN newsletter_subscribers s ON s.id = d.subscriber_id
                     WHERE s.email = '%s' AND d.period = '2040-03' AND d.sent_at IS NOT NULL""".formatted(e))).isEqualTo(1);
        }
        assertThat(third.sent()).isZero();
        // a new month = a new email
        newsletter.sendMonthly(YearMonth.of(2040, 4));
        emails.forEach(e -> assertThat(mailer.sentTo(e)).hasSize(2));
    }

    @Test
    void neverResendsAPostWhileUnsentPostsExist() {
        String email = unique("collector");
        UUID id = insertActive(email);
        long published = count("SELECT count(*) FROM posts WHERE status = 'PUBLISHED'");
        assertThat(published).isGreaterThan(1);

        for (int i = 0; i < published; i++) {
            newsletter.sendMonthly(YearMonth.of(2050, 1).plusMonths(i));
        }
        List<UUID> received = jdbc.sql("SELECT post_id FROM newsletter_deliveries WHERE subscriber_id = :id")
                .param("id", id).query(UUID.class).list();
        assertThat(received).hasSize((int) published).doesNotHaveDuplicates();

        // everything received: the fallback still sends something next month
        newsletter.sendMonthly(YearMonth.of(2050, 1).plusMonths(published));
        assertThat(mailer.sentTo(email)).hasSize((int) published + 1);
    }

    @Test
    void anSmtpFailureReleasesTheClaimSoTheNextRunRetries() {
        String email = unique("flaky");
        insertActive(email);
        YearMonth month = YearMonth.of(2045, 6);

        mailer.failing.set(true);
        SendResult failed = newsletter.sendMonthly(month);
        assertThat(failed.failed()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM newsletter_deliveries d JOIN newsletter_subscribers s ON s.id = d.subscriber_id "
                + "WHERE s.email = '" + email + "' AND d.period = '2045-06'")).isZero();

        mailer.failing.set(false);
        newsletter.sendMonthly(month);
        assertThat(mailer.sentTo(email)).hasSize(1);
    }

    // ------------------------------------------------------------------ admin

    @Test
    void adminOverviewNeedsTheAdminRoleAndMasksEmails() throws Exception {
        String email = unique("masked");
        insertActive(email);

        assertThat(send("GET", "/api/v1/admin/newsletter", null, null).statusCode()).isEqualTo(401);

        HttpResponse<String> res = send("GET", "/api/v1/admin/newsletter", null, adminJwt());
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.get("counts").get("ACTIVE").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(res.body()).doesNotContain(email).contains("ma***@pulse.test");

        HttpResponse<String> run = send("POST", "/api/v1/admin/newsletter/send-monthly", "", adminJwt());
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(json.readTree(run.body()).get("period").asString()).isEqualTo(NewsletterService.currentPeriod());
        assertThat(mailer.sentTo(email)).hasSize(1);
    }

    @Test
    void emailMasking() {
        assertThat(NewsletterService.maskEmail("ankur.kumar@gmail.com")).isEqualTo("an***@gmail.com");
        assertThat(NewsletterService.maskEmail("a@x.io")).isEqualTo("a***@x.io");
    }

    // ------------------------------------------------------------------ helpers

    private static String unique(String prefix) {
        return prefix.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@pulse.test";
    }

    private void confirm(String email) throws Exception {
        String token = extract(CONFIRM_LINK, mailer.sentTo(email).getLast().text());
        assertThat(post("/api/v1/newsletter/confirm", Map.of("token", token)).statusCode()).isEqualTo(200);
    }

    /** An ACTIVE subscriber straight in the database (the job tests don't need the opt-in dance). */
    private UUID insertActive(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO newsletter_subscribers (id, email, status, unsubscribe_token_hash, confirmed_at)
                        VALUES (:id, :e, 'ACTIVE', :h, now())""")
                .param("id", id).param("e", email).param("h", NewsletterTokens.sha256Hex(tokens.unsubscribeToken(id))).update();
        return id;
    }

    private Map<String, Object> subscriber(String email) {
        return jdbc.sql("SELECT * FROM newsletter_subscribers WHERE email = :e").param("e", email).query().singleRow();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String extract(Pattern p, String text) {
        Matcher m = p.matcher(text);
        assertThat(m.find()).as("link in: " + text).isTrue();
        return m.group(1);
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

    /** A real, signed admin access token (same claims as TokenService) — no login needed for these tests. */
    private String adminJwt() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(authProps.issuer()).subject("ankur")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of("ADMIN")).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
