package com.pulse.content.newsletter;

import com.pulse.content.mail.OutgoingEmail;
import com.pulse.content.newsletter.NewsletterRepository.Article;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The newsletter email templates: plain Java text blocks, no template engine needed for three short emails.
 *
 * <p><b>XSS in email:</b> every value that came from a user (their name) or from the database (article title,
 * summary) goes through {@link #esc(String)} before it is put into HTML. Without it, a "name" like
 * {@code <a href="evil">Click</a>} would become a real link inside an email that looks like it came from us.
 * The plain-text version needs no escaping: text is never interpreted.
 *
 * <p>Links point to the WEBSITE ({@code pulse.web.base-url}), not to the API: the website page shows a friendly
 * screen and calls the API with a POST. (A GET that changed state would be "clicked" by mail scanners that
 * pre-fetch links to check them for malware — they would confirm or unsubscribe people by accident.)
 */
@Component
public class NewsletterEmails {

    private final String baseUrl;

    public NewsletterEmails(@Value("${pulse.web.base-url:http://localhost:3000}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String confirmUrl(String token) {
        return baseUrl + "/newsletter/confirm?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    public String unsubscribeUrl(String token) {
        return baseUrl + "/newsletter/unsubscribe?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    String articleUrl(Article a) {
        return baseUrl + "/posts/" + URLEncoder.encode(a.slug(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the three emails

    /** Double opt-in step 1: "click to confirm". No unsubscribe link: they are not subscribed yet. */
    public OutgoingEmail confirm(String to, String name, String confirmUrl) {
        String text = """
                Hi %s,

                Please confirm that you want the Pulse newsletter (one hand-picked article per month):
                %s

                This link is valid for 48 hours. If you did not ask for this, ignore this email —
                you will not be subscribed and we will not write again.
                """.formatted(greetingName(name), confirmUrl);
        String body = """
                <p>Hi %s,</p>
                <p>Please confirm that you want the Pulse newsletter: one hand-picked article per month.</p>
                %s
                <p style="color:#6b7280;font-size:13px">This link is valid for 48 hours. If you did not ask for this,
                ignore this email: you will not be subscribed and we will not write again.</p>
                """.formatted(esc(greetingName(name)), button(confirmUrl, "Confirm my subscription"));
        return new OutgoingEmail(to, null, "Confirm your Pulse subscription", text, layout("Confirm your subscription", body), Map.of());
    }

    /** Sent right after confirming. {@code article} is null if there is nothing to send (no posts yet / already sent this month). */
    public OutgoingEmail welcome(String to, String name, Article article, String unsubscribeUrl) {
        String intro = "Welcome to Pulse! Once a month you will get one article from the blog.";
        return articleEmail(to, name, article, unsubscribeUrl, "Welcome to Pulse", intro,
                article == null ? null : "Here is your first one:");
    }

    /** The monthly email. */
    public OutgoingEmail monthly(String to, String name, Article article, String unsubscribeUrl) {
        return articleEmail(to, name, article, unsubscribeUrl, "Your Pulse article of the month: " + oneLine(article.title()),
                "Here is this month's pick from Pulse.", null);
    }

    // ------------------------------------------------------------------ building blocks

    private OutgoingEmail articleEmail(String to, String name, Article article, String unsubscribeUrl,
                                       String subject, String intro, String lead) {
        StringBuilder text = new StringBuilder("Hi " + greetingName(name) + ",\n\n" + intro + "\n");
        StringBuilder html = new StringBuilder("<p>Hi " + esc(greetingName(name)) + ",</p><p>" + esc(intro) + "</p>");
        if (article != null) {
            String url = articleUrl(article);
            if (lead != null) {
                text.append(lead).append('\n');
                html.append("<p>").append(esc(lead)).append("</p>");
            }
            text.append("\n").append(article.title()).append('\n');
            if (article.summary() != null) {
                text.append(article.summary()).append('\n');
            }
            text.append("Read it: ").append(url).append('\n');
            html.append("<div style=\"border:1px solid #e5e7eb;border-radius:12px;padding:16px;margin:16px 0\">")
                    .append("<div style=\"font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:#6b7280\">")
                    .append(esc(article.section())).append("</div>")
                    .append("<h2 style=\"margin:6px 0 8px;font-size:20px\">").append(esc(article.title())).append("</h2>");
            if (article.summary() != null) {
                html.append("<p style=\"margin:0 0 12px;color:#374151\">").append(esc(article.summary())).append("</p>");
            }
            html.append(button(url, "Read the article")).append("</div>");
        }
        text.append("\n--\nYou get this because you subscribed to the Pulse newsletter.\nUnsubscribe: ")
                .append(unsubscribeUrl).append('\n');
        html.append("<p style=\"color:#6b7280;font-size:13px\">You get this because you subscribed to the Pulse newsletter. ")
                .append("<a href=\"").append(esc(unsubscribeUrl)).append("\" style=\"color:#6b7280\">Unsubscribe</a></p>");
        // List-Unsubscribe: mail apps (Gmail, Apple Mail…) show their own "Unsubscribe" button using this header.
        // Big senders must also support RFC 8058 one-click (List-Unsubscribe-Post), which needs a URL that accepts
        // a POST from the mail provider; ours is a website page, so we don't advertise one-click (yet).
        Map<String, String> headers = Map.of("List-Unsubscribe", "<" + unsubscribeUrl + ">");
        return new OutgoingEmail(to, null, subject, text.toString(), layout(subject, html.toString()), headers);
    }

    /** Minimal, email-client-safe HTML: tables and inline styles (most mail apps ignore &lt;style&gt; blocks). */
    private static String layout(String title, String content) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>%s</title></head>
                <body style="margin:0;padding:0;background:#f3f4f6">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f3f4f6;padding:24px 0">
                <tr><td align="center">
                <table role="presentation" width="560" cellspacing="0" cellpadding="0"
                       style="max-width:560px;width:100%%;background:#ffffff;border-radius:16px;padding:28px;
                              font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;line-height:1.55;color:#111827">
                <tr><td>
                <div style="font-weight:700;font-size:18px;margin-bottom:16px">Pulse</div>
                %s
                </td></tr></table>
                <div style="font-family:Arial,sans-serif;font-size:12px;color:#9ca3af;margin-top:12px">
                Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC</div>
                </td></tr></table>
                </body></html>
                """.formatted(esc(title), content);
    }

    private static String button(String url, String label) {
        return "<p><a href=\"" + esc(url) + "\" style=\"display:inline-block;background:#111827;color:#ffffff;"
                + "text-decoration:none;padding:10px 18px;border-radius:999px;font-weight:600\">" + esc(label) + "</a></p>";
    }

    private static String greetingName(String name) {
        return name == null || name.isBlank() ? "there" : name;
    }

    /** Subjects are headers: never allow line breaks in them (titles shouldn't have any, but be sure). */
    private static String oneLine(String s) {
        return s.replaceAll("\\p{Cntrl}", " ");
    }

    /** &lt; &gt; &amp; &quot; &#39; → entities: text stays text, never becomes markup. */
    static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
