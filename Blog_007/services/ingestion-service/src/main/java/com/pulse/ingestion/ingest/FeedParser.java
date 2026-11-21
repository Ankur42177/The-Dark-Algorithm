package com.pulse.ingestion.ingest;

import com.pulse.ingestion.fetch.FetchException;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses RSS/Atom with Rome — SAFELY. {@code setAllowDoctypes(false)} makes the XML parser reject any
 * document containing a DOCTYPE, which is how XXE attacks work:
 * <pre>&lt;!DOCTYPE x [ &lt;!ENTITY secret SYSTEM "file:///etc/passwd"&gt; ]&gt; … &amp;secret; …</pre>
 * Without this, the parser could read local files or call internal URLs while "parsing a feed".
 */
@Component
public class FeedParser {

    public record ParsedItem(String guid, String url, String title, String excerpt, String author, String imageUrl, Instant publishedAt) {
    }

    public List<ParsedItem> parse(byte[] xml, String feedUrl) {
        SyndFeed feed;
        try {
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(false);                       // XXE defence
            feed = input.build(new XmlReader(new ByteArrayInputStream(xml)));
        } catch (Exception e) {
            throw FetchException.permanent("Not a valid RSS/Atom feed: " + e.getClass().getSimpleName() + " " + firstLine(e.getMessage()));
        }
        List<ParsedItem> items = new ArrayList<>();
        for (SyndEntry e : feed.getEntries()) {
            String url = absoluteHttpUrl(e.getLink(), feedUrl);
            if (url == null || e.getTitle() == null || e.getTitle().isBlank()) {
                continue;                                        // no usable link/title → skip, never guess
            }
            String summary = e.getDescription() != null ? e.getDescription().getValue()
                    : !e.getContents().isEmpty() ? e.getContents().getFirst().getValue() : null;
            Instant published = e.getPublishedDate() != null ? e.getPublishedDate().toInstant()
                    : e.getUpdatedDate() != null ? e.getUpdatedDate().toInstant() : Instant.now();
            items.add(new ParsedItem(
                    e.getUri() != null ? e.getUri() : url,
                    url,
                    Text.clean(e.getTitle(), 300),
                    Text.excerpt(summary),
                    e.getAuthor() == null || e.getAuthor().isBlank() ? null : Text.clean(e.getAuthor(), 120),
                    imageOf(e),
                    published.isAfter(Instant.now()) ? Instant.now() : published));   // no future dates
        }
        return items;
    }

    private static String imageOf(SyndEntry e) {
        for (SyndEnclosure enc : e.getEnclosures()) {
            if (enc.getType() != null && enc.getType().startsWith("image/") && enc.getUrl() != null && enc.getUrl().startsWith("https://")) {
                return enc.getUrl();
            }
        }
        return null;
    }

    /** Only absolute http(s) links (relative ones resolved against the feed URL). javascript:, data: … are dropped. */
    static String absoluteHttpUrl(String link, String base) {
        if (link == null || link.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(base).resolve(link.trim());
            String scheme = u.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && u.getHost() != null ? u.toString() : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
