package com.pulse.content.contact;

import com.pulse.content.common.InputRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request/response shapes of the contact form API. */
public final class ContactDtos {

    private ContactDtos() {
    }

    /**
     * POST /api/v1/contact. Same limits as the database columns (defence in depth: Java rejects with a clear 400,
     * the database would reject anything that slipped through).
     *
     * @param website honeypot, see NewsletterDtos.SubscribeRequest
     */
    public record ContactRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = InputRules.SINGLE_LINE) String name,
            @NotBlank @Size(max = 254) @Email(regexp = InputRules.EMAIL) String email,
            @NotBlank @Size(max = 5000) @Pattern(regexp = InputRules.NO_NUL) String message,
            String website) {

        /** Runs before validation: surrounding spaces in the email (copy-paste) are not an error. */
        public ContactRequest {
            email = email == null ? null : email.strip();
        }
    }

    public record StatusResponse(String status) {
    }

    /** What the admin sees (full email: they need it to reply). */
    public record ContactMessageView(UUID id, String name, String email, String message, Instant createdAt, Instant handledAt) {
    }
}
