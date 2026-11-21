package com.pulse.content.contact;

import com.pulse.content.contact.ContactDtos.ContactRequest;
import com.pulse.content.contact.ContactDtos.StatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public contact form endpoint (no login). Rate-limited at the gateway ("forms" policy). */
@RestController
@Tag(name = "Contact")
public class ContactController {

    private final ContactService contact;

    public ContactController(ContactService contact) {
        this.contact = contact;
    }

    /** 202 {"status":"received"} — also for honeypot hits, so bots can't tell they were caught. */
    @PostMapping("/api/v1/contact")
    @Operation(summary = "Send a message to the site owner")
    public ResponseEntity<StatusResponse> send(@Valid @RequestBody ContactRequest req, HttpServletRequest http) {
        contact.receive(req, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new StatusResponse("received"));
    }
}
