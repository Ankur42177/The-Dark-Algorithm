package com.pulse.ingestion.ingest;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Editorial filter: "Around the web" should show ARTICLES worth reading, not everything a feed emits.
 * Even good feeds mix in live blogs, quizzes, podcasts, one-line quote posts and version-bump notes.
 *
 * <p>Runs after parsing and before storing, so a filtered item is never stored, published to Kafka, shown,
 * or embedded for the chatbot. Rules are deliberately simple and named, so a surprising drop is easy to explain
 * ({@link #reject} returns the rule's name).
 */
@Component
public class QualityFilter {

    private record Rule(String name, Pattern pattern) {
    }

    private static Rule rule(String name, String regex) {
        return new Rule(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    private static final List<Rule> TITLE_RULES = List.of(
            // "Manchester City v Fulham: Premier League – live", "… as it happened", "Live: …", "live blog"
            rule("live-blog", "(?:[–—-]\\s*live\\s*$|\\bas it happened\\b|^live\\s*[:|]|\\blive blog\\b)"),
            // "Who am I? Plus today's other quizzes", "Football quiz: …"
            rule("quiz", "\\bquiz(?:zes)?\\b|^who am i\\?"),
            // audio/video shows: "Football Daily", "Listener mailbag: …", "… podcast"
            rule("podcast", "\\bpodcast\\b|\\bmailbag\\b|^football (?:daily|weekly)\\b"),
            // link-only video posts: "Token fraud is here and it's BAD (YouTube)", "Gemini 4 keynote [video]".
            // The page is just an embedded player, nothing to read. Only a trailing marker counts, so
            // "How YouTube scales its video pipeline" is KEPT.
            rule("video-link", "[(\\[]\\s*(?:youtube|video)\\s*[)\\]]\\s*$"),
            // Simon Willison-style one-line quote posts: "Quoting Ravi Vakil"
            rule("quote-post", "^quoting\\s"),
            // bare version bumps: "llm-gemini 0.38", "Spring Boot 4.1.3 available now",
            // "Spring AI 2.1.0 GA and 2.0.3 GA Available Now", "Spring Cloud 2025.1.5 (aka Oakwood) Has Been Released".
            // Only release words may follow the version, so "Python 3.13 makes the GIL optional" is KEPT.
            rule("release-note", "^[\\p{L}\\d .+_-]{1,40}?\\s+v?\\d+(?:\\.\\d+){1,3}(?:\\s*\\([^)]{0,40}\\))?"
                    + "(?:\\s+(?:ga|and|is|now|out|has|been|available|released|v?\\d+(?:\\.\\d+){1,3}))*\\s*$")
    );

    /** @return the name of the rule that rejects this item, or empty if it's worth showing. */
    public Optional<String> reject(FeedParser.ParsedItem item) {
        String title = item.title() == null ? "" : item.title().strip();
        for (Rule r : TITLE_RULES) {
            if (r.pattern().matcher(title).find()) {
                return Optional.of(r.name());
            }
        }
        return Optional.empty();
    }

    public List<FeedParser.ParsedItem> keep(List<FeedParser.ParsedItem> items) {
        return items.stream().filter(i -> reject(i).isEmpty()).toList();
    }
}
