package com.pulse.content.mail;

import java.util.Map;

/**
 * One email, independent of HOW it is sent (SMTP, log, test capture).
 *
 * <p>Every email has BOTH an HTML body and a plain-text body ("multipart/alternative"): mail apps that block
 * HTML, screen readers and spam filters all prefer a text version, and spam filters distrust HTML-only mail.
 *
 * @param to      recipient address (already validated: no spaces / line breaks, so no header injection)
 * @param replyTo optional Reply-To address (contact notifications: "reply" goes to the visitor), may be null
 * @param subject subject line (never contains user input with line breaks)
 * @param text    plain-text body
 * @param html    HTML body — every user/article value inside it is HTML-escaped by the template
 * @param headers extra headers, e.g. {@code List-Unsubscribe}
 */
public record OutgoingEmail(String to, String replyTo, String subject, String text, String html, Map<String, String> headers) {

    public OutgoingEmail {
        headers = headers == null ? Map.of() : Map.copyOf(headers);   // records are immutable: copy defensively
    }
}
