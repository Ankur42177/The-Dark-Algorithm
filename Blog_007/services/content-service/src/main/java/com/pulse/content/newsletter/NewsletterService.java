package com.pulse.content.newsletter;

import com.pulse.content.common.InputRules;
import com.pulse.content.common.ResourceNotFoundException;
import com.pulse.content.mail.Mailer;
import com.pulse.content.mail.OutgoingEmail;
import com.pulse.content.newsletter.NewsletterDtos.AdminOverview;
import com.pulse.content.newsletter.NewsletterDtos.AdminSubscriberRow;
import com.pulse.content.newsletter.NewsletterDtos.SendResult;
import com.pulse.content.newsletter.NewsletterDtos.SubscribeRequest;
import com.pulse.content.newsletter.NewsletterRepository.Article;
import com.pulse.content.newsletter.NewsletterRepository.Subscriber;
import com.pulse.content.newsletter.NewsletterRepository.TokenOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The newsletter: double opt-in subscription + one random article per subscriber per month.
 *
 * <pre>
 *  subscribe(email) ──► row PENDING + confirm email ──► user clicks ──► website POSTs /confirm
 *                                                                       └► ACTIVE + welcome email (1st article)
 *  every 1st of the month 09:00 UTC ──► for each ACTIVE subscriber: claim (subscriber, month) → send → mark sent
 * </pre>
 *
 * <p><b>Why double opt-in?</b> Anyone can type anyone's address into a form. Only the owner of the mailbox can click
 * the link, so we never mail people who didn't ask (anti-spam law, and our sender reputation).
 *
 * <p><b>No email enumeration.</b> {@code subscribe} answers the same 202 "pending" whether the address is new,
 * pending or already active — otherwise the form would tell anyone "this person reads Pulse".
 */
@Service
public class NewsletterService {

    private static final Logger log = LoggerFactory.getLogger(NewsletterService.class);
    static final Duration CONFIRM_TTL = Duration.ofHours(48);
    private static final UUID BEFORE_ALL = new UUID(0, 0);   // 00000000-…: smaller than every real id
    private static final int BATCH = 100;

    private final NewsletterRepository repo;
    private final NewsletterTokens tokens;
    private final NewsletterEmails emails;
    private final Mailer mailer;
    private final Duration resendCooldown;

    public NewsletterService(NewsletterRepository repo, NewsletterTokens tokens, NewsletterEmails emails, Mailer mailer,
                             @Value("${pulse.newsletter.resend-cooldown:10m}") Duration resendCooldown) {
        this.repo = repo;
        this.tokens = tokens;
        this.emails = emails;
        this.mailer = mailer;
        this.resendCooldown = resendCooldown;
    }

    // ------------------------------------------------------------------ public flows

    /** Always "succeeds" from the caller's point of view (see "no enumeration" above). */
    public void subscribe(SubscribeRequest req) {
        if (req.website() != null && !req.website().isBlank()) {
            log.debug("Newsletter honeypot filled: ignoring request");
            return;                                                     // bot: pretend all is well, do nothing
        }
        String email = InputRules.normalizeEmail(req.email());
        String token = tokens.newConfirmToken();
        Optional<UUID> needsMail = repo.upsertPending(email, InputRules.blankToNull(req.name()),
                NewsletterTokens.sha256Hex(token), CONFIRM_TTL, resendCooldown);
        if (needsMail.isEmpty()) {
            return;                                                     // ACTIVE already, or a confirm mail just went out
        }
        // Sent AFTER the row is committed and OUTSIDE any transaction. If SMTP fails the user just submits again
        // (after the cooldown); we log instead of failing the request, which would also leak "this address is new".
        sendQuietly(emails.confirm(email, InputRules.blankToNull(req.name()), emails.confirmUrl(token)), "confirm");
    }

    /**
     * Confirm link clicked. 200 if the token is valid (also on a second click: idempotent), otherwise 404.
     * Only the request that actually flips PENDING → ACTIVE sends the welcome email.
     */
    public void confirm(String token) {
        TokenOwner owner = repo.findByValidConfirmToken(NewsletterTokens.sha256Hex(token))
                .orElseThrow(NewsletterService::invalidLink);
        if (!"PENDING".equals(owner.status())) {
            return;                                                     // already ACTIVE: double click, nothing to do
        }
        String unsubscribeHash = NewsletterTokens.sha256Hex(tokens.unsubscribeToken(owner.id()));
        repo.activate(owner.id(), unsubscribeHash).ifPresent(s -> deliver(s, currentPeriod(), true));
    }

    /** Idempotent: unsubscribing twice is still 200. Unknown token → 404. */
    public void unsubscribe(String token) {
        repo.unsubscribe(NewsletterTokens.sha256Hex(token)).orElseThrow(NewsletterService::invalidLink);
    }

    // ------------------------------------------------------------------ the monthly job

    /**
     * Mail every ACTIVE subscriber who has not had this month's email. Safe to run any number of times and on any
     * number of replicas in parallel: the claim (UNIQUE subscriber+month) decides who sends.
     */
    public SendResult sendMonthly(YearMonth month) {
        String period = month.toString();                              // "2026-11"
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        UUID after = BEFORE_ALL;
        List<Subscriber> batch;
        do {
            batch = repo.activeWithoutDelivery(period, after, BATCH);
            for (Subscriber s : batch) {
                switch (deliver(s, period, false)) {
                    case SENT -> sent++;
                    case FAILED -> failed++;
                    case SKIPPED -> skipped++;
                }
                after = s.id();
            }
        } while (batch.size() == BATCH);
        log.info("Newsletter {}: sent={} skipped={} failed={}", period, sent, skipped, failed);
        return new SendResult(period, sent, skipped, failed);
    }

    enum Outcome { SENT, SKIPPED, FAILED }

    /**
     * INSERT FIRST, THEN SEND, THEN MARK — the delivery-guarantee choice of this feature:
     * <ol>
     *   <li><b>claim</b>: insert (subscriber, month). Committed immediately, so every other replica sees it.</li>
     *   <li><b>send</b> the email.</li>
     *   <li><b>mark</b> sent_at.</li>
     * </ol>
     * If the process CRASHES between 1 and 3, the row stays claimed with sent_at NULL and nobody re-sends it: that
     * subscriber misses one month → <b>at-most-once</b>. The alternative (send first, record after) is
     * <b>at-least-once</b>: a crash after sending → the next run mails them AGAIN. For a newsletter a duplicate is
     * worse than a rare miss (annoyed readers, spam complaints, sender reputation), so we pick at-most-once.
     * When SMTP cleanly REJECTS the send (an exception, no crash), we release the claim so a later run can retry.
     */
    Outcome deliver(Subscriber s, String period, boolean welcome) {
        Optional<Article> article = repo.pickArticle(s.id());
        boolean claimed = article.isPresent() && repo.claim(s.id(), period, article.get().id());
        String unsubscribeUrl = emails.unsubscribeUrl(tokens.unsubscribeToken(s.id()));
        if (!claimed) {
            if (welcome) {                                              // still say welcome, just without an article
                sendQuietly(emails.welcome(s.email(), s.name(), null, unsubscribeUrl), "welcome");
            }
            return Outcome.SKIPPED;                                     // no posts, or another replica got this one
        }
        OutgoingEmail mail = welcome
                ? emails.welcome(s.email(), s.name(), article.get(), unsubscribeUrl)
                : emails.monthly(s.email(), s.name(), article.get(), unsubscribeUrl);
        try {
            mailer.send(mail);
        } catch (RuntimeException e) {
            repo.releaseClaim(s.id(), period);
            log.warn("Newsletter mail to subscriber {} failed ({}); will retry on the next run", s.id(), e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
        repo.markSent(s.id(), period);
        return Outcome.SENT;
    }

    // ------------------------------------------------------------------ admin

    public AdminOverview overview() {
        var counts = repo.countByStatus();
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        String period = currentPeriod();
        List<AdminSubscriberRow> recent = repo.recent(50).stream()
                .map(r -> new AdminSubscriberRow(r.id(), maskEmail(r.email()), r.name(), r.status(),
                        r.createdAt(), r.confirmedAt(), r.unsubscribedAt()))
                .toList();
        return new AdminOverview(counts, total, period, repo.sentInPeriod(period), recent);
    }

    /**
     * "ankur.kumar@gmail.com" → "an***@gmail.com". Data minimisation: the admin screen (and anyone looking over
     * the admin's shoulder, or a screenshot) doesn't need full addresses to see that sign-ups work.
     */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        return local.substring(0, Math.min(2, local.length())) + "***" + email.substring(at);
    }

    // ------------------------------------------------------------------ helpers

    static String currentPeriod() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    private void sendQuietly(OutgoingEmail mail, String kind) {
        try {
            mailer.send(mail);
        } catch (RuntimeException e) {
            log.warn("Could not send {} email ({})", kind, e.getClass().getSimpleName());
        }
    }

    private static ResourceNotFoundException invalidLink() {
        return new ResourceNotFoundException("invalid-token", "Invalid or expired link",
                "This link is unknown or has expired. Please subscribe again.");
    }
}
