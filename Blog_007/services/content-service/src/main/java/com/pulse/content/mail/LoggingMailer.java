package com.pulse.content.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback when NO SMTP server is configured (the default on a laptop): instead of sending, write the email to
 * the log. You can copy the confirm/unsubscribe link from the console and the whole flow works with zero infra.
 *
 * <p>Logged on purpose: subject, recipient and the plain-text body (which contains the links). That is personal
 * data and secret links — fine for local development, which is the only place this class runs. Production sets
 * {@code SPRING_MAIL_HOST}, which switches to {@link SmtpMailer}.
 */
public class LoggingMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

    public LoggingMailer() {
        log.warn("spring.mail.host is not set: emails are LOGGED, not sent (set SPRING_MAIL_HOST to send them)");
    }

    @Override
    public void send(OutgoingEmail email) {
        log.info("""

                ----- EMAIL (not sent: no SMTP configured) -----
                To: {}
                Subject: {}
                Headers: {}

                {}
                ------------------------------------------------""", email.to(), email.subject(), email.headers(), email.text());
    }
}
