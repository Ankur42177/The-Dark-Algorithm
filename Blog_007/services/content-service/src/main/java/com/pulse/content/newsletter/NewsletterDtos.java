package com.pulse.content.newsletter;

import com.pulse.content.common.InputRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of the newsletter API (public + admin). */
public final class NewsletterDtos {

    private NewsletterDtos() {
    }

    /**
     * POST /api/v1/newsletter/subscribe.
     *
     * @param website HONEYPOT. The form renders this input invisibly; humans leave it empty, naive bots fill
     *                every field. Non-empty → we answer 202 as usual and silently do nothing, so the bot learns
     *                nothing. Deliberately NOT validated (a 400 would tell the bot what we check).
     */
    public record SubscribeRequest(
            @NotBlank @Size(max = 254) @Email(regexp = InputRules.EMAIL) String email,
            @Size(max = 100) @Pattern(regexp = InputRules.SINGLE_LINE) String name,
            String website) {

        /** COMPACT CONSTRUCTOR: runs when Jackson builds the record, i.e. BEFORE validation → " a@b.co " is fine. */
        public SubscribeRequest {
            email = email == null ? null : email.strip();
        }
    }

    /** POST /confirm and /unsubscribe. Any non-matching token is a 404, so only "missing / absurdly long" is a 400. */
    public record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    /** {"status":"pending" | "active" | "unsubscribed"} */
    public record StatusResponse(String status) {
    }

    /** One subscriber as the admin sees it: the email is partly masked (an***@gmail.com). */
    public record AdminSubscriberRow(UUID id, String email, String name, String status,
                                     Instant createdAt, Instant confirmedAt, Instant unsubscribedAt) {
    }

    /** GET /api/v1/admin/newsletter */
    public record AdminOverview(Map<String, Long> counts, long total, String period, long sentThisPeriod,
                                List<AdminSubscriberRow> recent) {
    }

    /** POST /api/v1/admin/newsletter/send-monthly (and the result of the scheduled job). */
    public record SendResult(String period, int sent, int skipped, int failed) {
    }
}
