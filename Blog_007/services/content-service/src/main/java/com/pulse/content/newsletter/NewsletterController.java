package com.pulse.content.newsletter;

import com.pulse.content.newsletter.NewsletterDtos.StatusResponse;
import com.pulse.content.newsletter.NewsletterDtos.SubscribeRequest;
import com.pulse.content.newsletter.NewsletterDtos.TokenRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public newsletter API, called by the website (no login). Abuse protection lives in layers:
 * api-gateway rate limit "forms" (5 per 10 min per IP) → honeypot field → double opt-in → per-address resend cooldown.
 *
 * <p>Tokens travel in the JSON BODY of a POST, not in the URL of a GET: URLs end up in access logs, browser
 * history and Referer headers; bodies don't.
 */
@RestController
@RequestMapping("/api/v1/newsletter")
@Tag(name = "Newsletter")
public class NewsletterController {

    private static final CacheControl NO_STORE = CacheControl.noStore();
    private final NewsletterService newsletter;

    public NewsletterController(NewsletterService newsletter) {
        this.newsletter = newsletter;
    }

    /** 202 Accepted = "we got it; the real work (the user confirming) happens later". Same answer for every address. */
    @PostMapping("/subscribe")
    @Operation(summary = "Start a subscription (double opt-in: sends a confirmation email)")
    public ResponseEntity<StatusResponse> subscribe(@Valid @RequestBody SubscribeRequest req) {
        newsletter.subscribe(req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(NO_STORE).body(new StatusResponse("pending"));
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm with the token from the email (404 if unknown or expired)")
    public ResponseEntity<StatusResponse> confirm(@Valid @RequestBody TokenRequest req) {
        newsletter.confirm(req.token());
        return ResponseEntity.ok().cacheControl(NO_STORE).body(new StatusResponse("active"));
    }

    @PostMapping("/unsubscribe")
    @Operation(summary = "Unsubscribe with the token from any newsletter email (idempotent; 404 if unknown)")
    public ResponseEntity<StatusResponse> unsubscribe(@Valid @RequestBody TokenRequest req) {
        newsletter.unsubscribe(req.token());
        return ResponseEntity.ok().cacheControl(NO_STORE).body(new StatusResponse("unsubscribed"));
    }
}
