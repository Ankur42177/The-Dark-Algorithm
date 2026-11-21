package com.pulse.ingestion.ingest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Real titles seen in the seeded feeds on 2026-11-21: what must be dropped, and what must survive. */
class QualityFilterTest {

    private final QualityFilter filter = new QualityFilter();

    private static FeedParser.ParsedItem titled(String title) {
        return new FeedParser.ParsedItem("g", "https://example.com/a", title, "An excerpt", null, null, Instant.now());
    }

    @ParameterizedTest(name = "drops \"{0}\" ({1})")
    @CsvSource(delimiter = '|', value = {
            "Manchester City v Fulham: Premier League – live | live-blog",
            "Arsenal 2-1 Chelsea – as it happened                | live-blog",
            "Live: transfer deadline day                         | live-blog",
            "Who am I? Plus today's other quizzes               | quiz",
            "Football quiz: name the 2005 Liverpool XI          | quiz",
            "Football Daily                                     | podcast",
            "Listener mailbag: Manchester City, media coverage  | podcast",
            "Quoting Ravi Vakil                                 | quote-post",
            "llm-gemini 0.38                                    | release-note",
            "Spring Boot 4.1.3 available now                    | release-note",
            "Spring AI 2.1.0 GA and 2.0.3 GA Available Now      | release-note",
            "Spring Cloud 2025.1.5 (aka Oakwood) Has Been Released | release-note",
            "Token fraud is here and it’s BAD (YouTube)          | video-link",
            "Building agents with LangGraph (video)              | video-link",
            "Gemini 4 keynote in 10 minutes [Video]              | video-link",
            "Spring Office Hours ( YouTube )                     | video-link",
    })
    void dropsNonArticles(String title, String rule) {
        assertThat(filter.reject(titled(title))).hasValue(rule);
    }

    @ParameterizedTest(name = "keeps \"{0}\"")
    @ValueSource(strings = {
            "Brighton are rocking the Premier League elite. But can they mount a title tilt?",
            "Access, Not Chances: How Como Took Manchester United Apart",
            "How Cloudflare WAF protects against ruby2shell (CVE-2026-132825)",
            "Python 3.13 makes the GIL optional",                         // version number, but a real article
            "How YouTube scales its video pipeline",                      // "YouTube" mid-title is fine
            "Video understanding with Gemini: a practical guide",          // so is "video"
            "Delivering live sport to millions: inside Netflix's streaming stack",   // "live" mid-title is fine
            "Do good club managers necessarily make good international managers?",
            "Fragments",                                                  // short, but a real (Martin Fowler) post
    })
    void keepsArticles(String title) {
        assertThat(filter.reject(titled(title))).isEmpty();
    }
}
