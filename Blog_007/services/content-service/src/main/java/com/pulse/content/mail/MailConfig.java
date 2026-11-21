package com.pulse.content.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Picks the email transport at startup.
 *
 * <p>Spring Boot creates a {@link JavaMailSender} ONLY when {@code spring.mail.host} is set. We ask for it through
 * an {@link ObjectProvider} ("give me one if it exists, null otherwise") — a plain constructor parameter would make
 * the app fail to start without SMTP. Then:
 * <pre>
 *   spring.mail.host set   → SmtpMailer    (Mailpit on localhost:1025, or a real provider)
 *   spring.mail.host unset → LoggingMailer (prints emails to the console)
 * </pre>
 * Gotcha: application.yml says {@code host: ${SPRING_MAIL_HOST:}}, i.e. an EMPTY string when unset — and for
 * Spring Boot "present but empty" still counts as set, so it creates a (useless) JavaMailSender. That's why we
 * check the host ourselves instead of trusting "a JavaMailSender bean exists".
 */
@Configuration
public class MailConfig {

    @Bean
    Mailer mailer(ObjectProvider<JavaMailSender> smtp,
                  @Value("${spring.mail.host:}") String host,
                  @Value("${pulse.mail.from:Pulse <newsletter@pulse.local>}") String from) {
        JavaMailSender sender = host.isBlank() ? null : smtp.getIfAvailable();
        return sender != null ? new SmtpMailer(sender, from) : new LoggingMailer();
    }
}
