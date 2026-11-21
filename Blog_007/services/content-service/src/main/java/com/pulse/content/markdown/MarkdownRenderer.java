package com.pulse.content.markdown;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Markdown → HTML → SANITIZED HTML. Done ONCE when a post is saved, never per page view (PLAN §7.2).
 *
 * <p>Why sanitize Ankur's own posts? Defence in depth: a stolen admin session, a pasted snippet, or a
 * future multi-author feature must not be able to put {@code <script>} into pages every reader loads
 * (stored XSS). The sanitizer is an ALLOW-list: only known-safe tags/attributes survive.
 */
@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    /** Allowed: formatting, links (http/https/mailto only), images (https), tables, code blocks. Nothing else. */
    private static final PolicyFactory POLICY = Sanitizers.FORMATTING
            .and(Sanitizers.BLOCKS)
            .and(Sanitizers.LINKS)
            .and(Sanitizers.TABLES)
            .and(Sanitizers.IMAGES)
            .and(new HtmlPolicyBuilder()
                    .allowElements("pre", "code", "hr", "figure", "figcaption")
                    .allowAttributes("class").matching(true, "language-java", "language-sql", "language-bash",
                            "language-yaml", "language-json", "language-ts", "language-js", "language-python", "language-text")
                    .onElements("code")
                    .toFactory());

    public MarkdownRenderer() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        // Raw HTML typed inside Markdown is escaped (shown as text), not passed through.
        options.set(HtmlRenderer.ESCAPE_HTML, true);
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    public String toSafeHtml(String markdown) {
        String html = renderer.render(parser.parse(markdown == null ? "" : markdown));
        return POLICY.sanitize(html);
    }

    /** ~200 words per minute, at least 1. */
    public static int readingMinutes(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return 1;
        }
        int words = markdown.trim().split("\\s+").length;
        return Math.max(1, (int) Math.round(words / 200.0));
    }
}
