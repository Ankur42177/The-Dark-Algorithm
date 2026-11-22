package com.pulse.content.contact;

import com.pulse.content.common.InputRules;
import com.pulse.content.common.ResourceNotFoundException;
import com.pulse.content.contact.ContactDtos.ContactMessageView;
import com.pulse.content.contact.ContactDtos.ContactRequest;
import com.pulse.content.mail.MailDispatcher;
import com.pulse.content.mail.OutgoingEmail;
import com.pulse.content.newsletter.NewsletterTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contact form: store the message, optionally email the owner, list/mark-handled for the admin.
 * Small enough that the SQL lives here (JdbcClient) instead of a separate repository class.
 */
@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final JdbcClient jdbc;
    private final MailDispatcher mail;
    private final String notifyAddress;
    private final byte[] ipHashKey;

    public ContactService(JdbcClient jdbc, MailDispatcher mail,
                          @Value("${pulse.contact.notify:}") String notifyAddress,
                          @Value("${pulse.contact.ip-hash-secret:}") String ipHashSecret, Environment env) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.notifyAddress = notifyAddress == null || notifyAddress.isBlank() ? null : notifyAddress.trim();
        if (ipHashSecret == null || ipHashSecret.isBlank()) {
            // Replicas with different keys would hash the same IP differently: "50 messages from one sender"
            // becomes invisible. Fail fast outside dev/test (PULSE_IP_HASH_SECRET).
            NewsletterTokens.requireDevOrTest(env, "pulse.contact.ip-hash-secret (PULSE_IP_HASH_SECRET)");
            this.ipHashKey = new byte[32];               // per-process key: hashes still group messages until a restart
            new SecureRandom().nextBytes(ipHashKey);
        } else {
            this.ipHashKey = ipHashSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * @param clientIp the visitor's IP. Behind the gateway this is the real client (Tomcat's RemoteIpValve reads
     *                 X-Forwarded-For only from trusted proxies — see application.yml).
     */
    public void receive(ContactRequest req, String clientIp) {
        if (req.website() != null && !req.website().isBlank()) {
            log.debug("Contact honeypot filled: ignoring request");
            return;
        }
        String name = req.name().trim();
        String email = InputRules.normalizeEmail(req.email());
        String message = req.message().strip();
        UUID id = jdbc.sql("""
                        INSERT INTO contact_messages (name, email, message, ip_hash)
                        VALUES (:name, :email, :message, :ip) RETURNING id""")
                .param("name", name).param("email", email).param("message", message).param("ip", hashIp(clientIp))
                .query(UUID.class).single();
        log.info("Contact message {} received", id);
        if (notifyAddress != null) {
            // The message is safely stored; the notification goes out in the background, and a failed one must
            // not turn into an error (or a slow response) for the visitor.
            mail.dispatch(notification(name, email, message), "contact notification");
        }
    }

    public List<ContactMessageView> recent() {
        return jdbc.sql("""
                        SELECT id, name, email, message, created_at, handled_at
                          FROM contact_messages ORDER BY created_at DESC, id DESC LIMIT 100""")
                .query((rs, n) -> new ContactMessageView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getString("message"), rs.getTimestamp("created_at").toInstant(),
                        Optional.ofNullable(rs.getTimestamp("handled_at")).map(Timestamp::toInstant).orElse(null)))
                .list();
    }

    /** Idempotent: marking twice keeps the FIRST handled_at (coalesce). Unknown id → 404. */
    public ContactMessageView markHandled(UUID id) {
        return jdbc.sql("""
                        UPDATE contact_messages SET handled_at = coalesce(handled_at, now())
                         WHERE id = :id
                        RETURNING id, name, email, message, created_at, handled_at""")
                .param("id", id)
                .query((rs, n) -> new ContactMessageView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("email"), rs.getString("message"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("handled_at").toInstant()))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("contact-message-not-found", "Message not found",
                        "No contact message with id " + id));
    }

    /**
     * HMAC-SHA256(serverKey, ip). Why not plain SHA-256(ip)? There are only ~4 billion IPv4 addresses: an attacker
     * with the database could hash them all in minutes and reverse every value. With a secret key mixed in, that
     * table can't be built without the key, yet the SAME IP still gives the SAME hash (useful to spot a spammer).
     */
    String hashIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ipHashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Email to the site owner. Reply-To = the visitor, so "Reply" in the mail app answers them directly. */
    private OutgoingEmail notification(String name, String email, String message) {
        String subject = "Pulse contact form: message from " + name;   // name has no line breaks (validated)
        String text = "From: " + name + " <" + email + ">\n\n" + message + "\n";
        String html = "<p><strong>From:</strong> " + esc(name) + " &lt;" + esc(email) + "&gt;</p>"
                + "<p style=\"white-space:pre-wrap\">" + esc(message) + "</p>";
        return new OutgoingEmail(notifyAddress, email, subject, text, html, Map.of());
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
