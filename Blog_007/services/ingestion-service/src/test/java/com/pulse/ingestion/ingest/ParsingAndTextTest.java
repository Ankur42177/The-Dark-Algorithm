package com.pulse.ingestion.ingest;

import com.pulse.ingestion.fetch.FetchException;
import com.pulse.ingestion.fetch.RobotsTxtTestAccess;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsingAndTextTest {

    private final FeedParser parser = new FeedParser();

    @Test
    void parsesRssAndCleansText() {
        String rss = """
                <?xml version="1.0"?><rss version="2.0"><channel><title>t</title><link>https://ex.com</link><description>d</description>
                <item><title>Hello &amp; welcome</title><link>/posts/1</link>
                  <description><![CDATA[<p>Intro <b>bold</b><script>alert(1)</script></p>]]></description>
                  <pubDate>Sat, 21 Nov 2026 06:00:00 GMT</pubDate></item>
                <item><title>Bad link</title><link>javascript:alert(1)</link></item>
                </channel></rss>""";
        var items = parser.parse(rss.getBytes(StandardCharsets.UTF_8), "https://ex.com/feed");
        assertThat(items).hasSize(1);                                       // javascript: link dropped
        assertThat(items.getFirst().url()).isEqualTo("https://ex.com/posts/1");
        assertThat(items.getFirst().title()).isEqualTo("Hello & welcome");
        assertThat(items.getFirst().excerpt()).isEqualTo("Intro bold");     // tags + script removed
    }

    @Test
    void xxePayloadIsRejected() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [ <!ENTITY secret SYSTEM "file:///etc/passwd"> ]>
                <rss version="2.0"><channel><title>&secret;</title><item><title>x</title><link>https://ex.com/a</link></item></channel></rss>""";
        assertThatThrownBy(() -> parser.parse(xxe.getBytes(StandardCharsets.UTF_8), "https://ex.com/feed"))
                .isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isTransient()).isFalse());
    }

    @Test
    void excerptsAreAtMost300Chars() {
        String longText = "word ".repeat(200);
        assertThat(Text.excerpt(longText)).hasSizeLessThanOrEqualTo(300).endsWith("…");
    }

    @Test
    void canonicalUrlsDropTrackingAndFragments() {
        assertThat(RawItemStore.canonical("HTTPS://Ex.com/a?utm_source=x&id=7#top")).isEqualTo("https://ex.com/a?id=7");
        assertThat(RawItemStore.sha256("https://ex.com/a")).hasSize(64);
    }

    @Test
    void robotsTxtGroups() {
        String robots = """
                User-agent: *
                Disallow: /private

                User-agent: PulseBot
                User-agent: OtherBot
                Disallow: /feeds/secret
                """;
        assertThat(RobotsTxtTestAccess.disallows(robots)).containsExactly("/feeds/secret");
        assertThat(RobotsTxtTestAccess.disallows("User-agent: *\nDisallow: /a\nDisallow: /b")).containsExactly("/a", "/b");
        assertThat(RobotsTxtTestAccess.disallows("")).isEmpty();
    }
}
