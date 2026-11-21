package com.pulse.content.newsletter;

import com.pulse.content.newsletter.NewsletterDtos.AdminOverview;
import com.pulse.content.newsletter.NewsletterDtos.SendResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Admin view of the newsletter. Protected by the existing rule {@code /api/v1/admin/** → ROLE_ADMIN}
 * (SecurityConfig) + CSRF token on the POST, exactly like the post editor.
 */
@RestController
@RequestMapping("/api/v1/admin/newsletter")
@Tag(name = "Admin newsletter")
public class AdminNewsletterController {

    private static final CacheControl NO_STORE = CacheControl.noStore();
    private final NewsletterService newsletter;

    public AdminNewsletterController(NewsletterService newsletter) {
        this.newsletter = newsletter;
    }

    @GetMapping
    @Operation(summary = "Subscriber counts by status + the 50 newest subscribers (emails masked)")
    public ResponseEntity<AdminOverview> overview() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(newsletter.overview());
    }

    /** Safe to press twice: people who already got this month's email are skipped. */
    @PostMapping("/send-monthly")
    @Operation(summary = "Run this month's newsletter now (idempotent per subscriber and month)")
    public ResponseEntity<SendResult> sendMonthly() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(newsletter.sendMonthly(YearMonth.now(ZoneOffset.UTC)));
    }
}
