package com.pulse.content.mail;

/**
 * Our OWN tiny interface for "send an email". The newsletter and contact code depend on THIS, not on
 * JavaMail, so the transport is pluggable:
 * <ul>
 *   <li>{@link SmtpMailer} — real SMTP (Mailpit locally, a real provider in production);</li>
 *   <li>{@link LoggingMailer} — no SMTP configured: print the email to the log (zero-infra local dev);</li>
 *   <li>a capturing fake in tests.</li>
 * </ul>
 * This is the "ports and adapters" idea: business code talks to a port, adapters plug in behind it.
 */
public interface Mailer {

    /** Sends synchronously. Throws a RuntimeException if the email could not be handed to the transport. */
    void send(OutgoingEmail email);
}
