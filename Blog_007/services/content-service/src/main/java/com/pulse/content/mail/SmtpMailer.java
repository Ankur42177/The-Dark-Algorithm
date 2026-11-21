package com.pulse.content.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends through Spring's {@link JavaMailSender} (auto-configured by Spring Boot when {@code spring.mail.host}
 * is set). {@link MimeMessageHelper} builds the MIME structure for us: multipart/alternative with a text and an
 * HTML part, UTF-8 everywhere, and properly encoded headers.
 */
public class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailer(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(OutgoingEmail email) {
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");   // true = multipart
            helper.setFrom(from);                    // "Pulse <newsletter@pulse.local>" is parsed into name + address
            helper.setTo(email.to());
            if (email.replyTo() != null) {
                helper.setReplyTo(email.replyTo());
            }
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());   // (plain, html): mail apps pick the best one they support
            for (var h : email.headers().entrySet()) {
                msg.setHeader(h.getKey(), h.getValue());
            }
            sender.send(msg);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build email", e);
        }
    }
}
