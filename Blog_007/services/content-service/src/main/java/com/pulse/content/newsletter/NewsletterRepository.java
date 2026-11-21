package com.pulse.content.newsletter;

import com.pulse.content.newsletter.NewsletterDtos.AdminSubscriberRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All newsletter SQL, with {@link JdbcClient} (plain SQL, like the feed and outbox code).
 *
 * <p>Note: there is NO {@code @Transactional} here. Each statement runs in its own tiny transaction
 * ("auto-commit"), and every state change is ONE atomic statement ({@code INSERT … ON CONFLICT},
 * {@code UPDATE … WHERE status = … RETURNING}). That is what makes the flows race-safe without locks, and it
 * lets the service send emails OUTSIDE any database transaction (never hold a DB connection while waiting on SMTP).
 */
@Repository
public class NewsletterRepository {

    /** Who to mail. {@code name} may be null. */
    public record Subscriber(UUID id, String email, String name) {
    }

    /** Who a confirm token belongs to, and whether they are still PENDING or already ACTIVE. */
    public record TokenOwner(UUID id, String status) {
    }

    /** The article we put in an email. */
    public record Article(UUID id, String slug, String title, String summary, String section) {
    }

    private final JdbcClient jdbc;

    public NewsletterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ subscribe / confirm / unsubscribe

    /**
     * One atomic UPSERT decides whether a confirmation email must go out:
     * <ul>
     *   <li>new address → INSERT a PENDING row → returns its id (send the email);</li>
     *   <li>existing PENDING / UNSUBSCRIBED row → new token, back to PENDING → returns id (send the email) —
     *       but only if the last confirm email is older than {@code cooldown};</li>
     *   <li>ACTIVE, or a confirm email was sent moments ago → the {@code WHERE} of DO UPDATE is false, nothing
     *       changes, no row is returned (send nothing).</li>
     * </ul>
     * The cooldown stops "email bombing": without it, a script could make us send a victim hundreds of confirm
     * emails (from many IPs, so the gateway's per-IP limit alone wouldn't stop it).
     */
    public Optional<UUID> upsertPending(String email, String name, String confirmTokenHash, Duration confirmTtl, Duration cooldown) {
        return jdbc.sql("""
                        INSERT INTO newsletter_subscribers (email, name, status, confirm_token_hash, confirm_expires_at, confirm_sent_at)
                        VALUES (:email, :name, 'PENDING', :hash, now() + make_interval(secs => :ttl), now())
                        ON CONFLICT (email) DO UPDATE
                           SET status             = 'PENDING',
                               name               = coalesce(EXCLUDED.name, newsletter_subscribers.name),
                               confirm_token_hash = EXCLUDED.confirm_token_hash,
                               confirm_expires_at = EXCLUDED.confirm_expires_at,
                               confirm_sent_at    = now()
                         WHERE newsletter_subscribers.status <> 'ACTIVE'
                           AND (newsletter_subscribers.confirm_sent_at IS NULL
                                OR newsletter_subscribers.confirm_sent_at < now() - make_interval(secs => :cooldown))
                        RETURNING id""")
                .param("email", email).param("name", name).param("hash", confirmTokenHash)
                .param("ttl", confirmTtl.toSeconds()).param("cooldown", cooldown.toSeconds())
                .query(UUID.class).optional();
    }

    /** The subscriber a still-valid confirm token belongs to, with its status (PENDING or ACTIVE). */
    public Optional<TokenOwner> findByValidConfirmToken(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, status FROM newsletter_subscribers
                         WHERE confirm_token_hash = :h AND confirm_expires_at > now()""")
                .param("h", tokenHash)
                .query((rs, n) -> new TokenOwner(rs.getObject("id", UUID.class), rs.getString("status")))
                .optional();
    }

    /**
     * PENDING → ACTIVE. {@code WHERE status = 'PENDING'} makes it a compare-and-set: if the link is clicked twice at
     * the same moment, only ONE update returns the row, so only one welcome email is sent.
     */
    public Optional<Subscriber> activate(UUID id, String unsubscribeTokenHash) {
        return jdbc.sql("""
                        UPDATE newsletter_subscribers
                           SET status = 'ACTIVE', confirmed_at = now(), unsubscribed_at = NULL,
                               unsubscribe_token_hash = :uh
                         WHERE id = :id AND status = 'PENDING'
                        RETURNING id, email, name""")
                .param("id", id).param("uh", unsubscribeTokenHash)
                .query((rs, n) -> new Subscriber(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name")))
                .optional();
    }

    /**
     * → UNSUBSCRIBED. Also deletes the confirm token, so an old confirm link can't silently re-subscribe them, and
     * clears {@code confirm_sent_at}: otherwise "subscribe → confirm → unsubscribe → subscribe again" within the
     * 10-minute cooldown answered 202 but sent nothing and left the row UNSUBSCRIBED (bug found in the Phase 7 review).
     * Returns the subscriber's status AFTER the call, or empty if the token is unknown.
     */
    public Optional<String> unsubscribe(String unsubscribeTokenHash) {
        int changed = jdbc.sql("""
                        UPDATE newsletter_subscribers
                           SET status = 'UNSUBSCRIBED', unsubscribed_at = now(),
                               confirm_token_hash = NULL, confirm_expires_at = NULL,
                               confirm_sent_at = NULL   -- a re-subscribe right after leaving must get its confirm email
                         WHERE unsubscribe_token_hash = :h AND status <> 'UNSUBSCRIBED'""")
                .param("h", unsubscribeTokenHash).update();
        if (changed > 0) {
            return Optional.of("UNSUBSCRIBED");
        }
        return jdbc.sql("SELECT status FROM newsletter_subscribers WHERE unsubscribe_token_hash = :h")
                .param("h", unsubscribeTokenHash).query(String.class).optional();
    }

    // ------------------------------------------------------------------ deliveries

    /**
     * A random PUBLISHED post this subscriber has not received yet; if they have received them all, any post.
     * Both in ONE query: sort "already received?" first (false before true), then randomly.
     *
     * <p>Scaling note: {@code ORDER BY random()} gives every row a random number and sorts ALL published posts —
     * fine for a blog (hundreds of posts), slow with millions. Alternatives at scale: pick a random id range /
     * {@code TABLESAMPLE SYSTEM}, or load the published ids once per job run, subtract each subscriber's
     * received set in memory and pick one at random there.
     */
    public Optional<Article> pickArticle(UUID subscriberId) {
        return jdbc.sql("""
                        SELECT p.id, p.slug, p.title, p.summary, p.section
                          FROM posts p
                         WHERE p.status = 'PUBLISHED'
                         ORDER BY EXISTS (SELECT 1 FROM newsletter_deliveries d
                                           WHERE d.subscriber_id = :sid AND d.post_id = p.id),
                                  random()
                         LIMIT 1""")
                .param("sid", subscriberId)
                .query((rs, n) -> new Article(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
                        rs.getString("summary"), rs.getString("section")))
                .optional();
    }

    /**
     * CLAIM this month's email for this subscriber. The UNIQUE (subscriber_id, period) constraint guarantees that
     * among any number of replicas / job runs, exactly one INSERT succeeds; the others get 0 rows → they skip.
     */
    public boolean claim(UUID subscriberId, String period, UUID postId) {
        return jdbc.sql("""
                        INSERT INTO newsletter_deliveries (subscriber_id, period, post_id)
                        VALUES (:sid, :period, :pid)
                        ON CONFLICT (subscriber_id, period) DO NOTHING""")
                .param("sid", subscriberId).param("period", period).param("pid", postId)
                .update() == 1;
    }

    public void markSent(UUID subscriberId, String period) {
        jdbc.sql("UPDATE newsletter_deliveries SET sent_at = now() WHERE subscriber_id = :sid AND period = :period")
                .param("sid", subscriberId).param("period", period).update();
    }

    /** The SMTP server said no: give the claim back so a later run may retry this subscriber. */
    public void releaseClaim(UUID subscriberId, String period) {
        jdbc.sql("DELETE FROM newsletter_deliveries WHERE subscriber_id = :sid AND period = :period AND sent_at IS NULL")
                .param("sid", subscriberId).param("period", period).update();
    }

    /**
     * One batch of ACTIVE subscribers that have no delivery for {@code period} yet, in id order after {@code afterId}
     * (KEYSET pagination, never OFFSET: rows inserted during the run can't make us skip or repeat anyone).
     */
    public List<Subscriber> activeWithoutDelivery(String period, UUID afterId, int limit) {
        return jdbc.sql("""
                        SELECT s.id, s.email, s.name FROM newsletter_subscribers s
                         WHERE s.status = 'ACTIVE' AND s.id > :after
                           AND NOT EXISTS (SELECT 1 FROM newsletter_deliveries d
                                            WHERE d.subscriber_id = s.id AND d.period = :period)
                         ORDER BY s.id
                         LIMIT :limit""")
                .param("period", period).param("after", afterId).param("limit", limit)
                .query((rs, n) -> new Subscriber(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name")))
                .list();
    }

    // ------------------------------------------------------------------ admin

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PENDING", 0L);
        counts.put("ACTIVE", 0L);
        counts.put("UNSUBSCRIBED", 0L);
        jdbc.sql("SELECT status, count(*) AS n FROM newsletter_subscribers GROUP BY status")
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    public long sentInPeriod(String period) {
        return jdbc.sql("SELECT count(*) FROM newsletter_deliveries WHERE period = :p AND sent_at IS NOT NULL")
                .param("p", period).query(Long.class).single();
    }

    /** Newest subscribers; the email is masked by the service before it leaves the server. */
    public List<AdminSubscriberRow> recent(int limit) {
        return jdbc.sql("""
                        SELECT id, email, name, status, created_at, confirmed_at, unsubscribed_at
                          FROM newsletter_subscribers ORDER BY created_at DESC, id DESC LIMIT :limit""")
                .param("limit", limit)
                .query((rs, n) -> new AdminSubscriberRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("name"), rs.getString("status"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("confirmed_at")), instant(rs.getTimestamp("unsubscribed_at"))))
                .list();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
