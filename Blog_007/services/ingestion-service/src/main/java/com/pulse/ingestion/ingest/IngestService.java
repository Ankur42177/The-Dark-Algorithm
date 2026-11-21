package com.pulse.ingestion.ingest;

import com.pulse.ingestion.fetch.FetchException;
import com.pulse.ingestion.fetch.FetchResult;
import com.pulse.ingestion.fetch.RobotsTxt;
import com.pulse.ingestion.fetch.SafeFetcher;
import com.pulse.ingestion.queue.FetchFeedMessage;
import com.pulse.ingestion.source.FeedSource;
import com.pulse.ingestion.source.FeedSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * One job = one feed: robots.txt → conditional GET → parse → store NEW items → publish them to Kafka.
 * Every step is safe to repeat, because RabbitMQ may deliver the same job twice.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final FeedSourceRepository sources;
    private final SafeFetcher fetcher;
    private final RobotsTxt robots;
    private final FeedParser parser;
    private final RawItemStore store;
    private final ArticlePublisher publisher;
    private final FetchRunRepository runs;
    private final QualityFilter quality;

    public IngestService(FeedSourceRepository sources, SafeFetcher fetcher, RobotsTxt robots, FeedParser parser,
                         RawItemStore store, ArticlePublisher publisher, FetchRunRepository runs, QualityFilter quality) {
        this.quality = quality;
        this.sources = sources;
        this.fetcher = fetcher;
        this.robots = robots;
        this.parser = parser;
        this.store = store;
        this.publisher = publisher;
        this.runs = runs;
    }

    public void process(FetchFeedMessage job) {
        FeedSource src = sources.findById(job.sourceId()).orElse(null);
        if (src == null || !src.active()) {
            return;                                                       // deleted or disabled meanwhile
        }
        UUID run = runs.start(src.id(), job.messageId(), job.attempt());
        if (!robots.isAllowed(src.feedUrl())) {
            runs.finish(run, "SKIPPED", null, 0, 0, "robots.txt disallows PulseBot");
            return;
        }
        try {
            FetchResult r = fetcher.fetch(src.feedUrl(), src.etag(), src.lastModified());
            if (r.notModified()) {
                publisher.publishPending(src.id());                       // leftovers from an earlier failed publish
                sources.recordSuccess(src.id(), r.etag(), r.lastModified());
                runs.finish(run, "NOT_MODIFIED", 304, 0, 0, null);
                return;
            }
            List<FeedParser.ParsedItem> items = parser.parse(r.body(), r.finalUrl());
            List<FeedParser.ParsedItem> worthy = quality.keep(items);          // drop live blogs, quizzes, podcasts, …
            int fresh = store.saveNew(src, worthy);
            int published = publisher.publishPending(src.id());
            sources.recordSuccess(src.id(), r.etag(), r.lastModified());
            runs.finish(run, "OK", r.status(), items.size(), fresh, null);
            log.info("{}: {} items, {} filtered out, {} new, {} published", src.name(), items.size(), items.size() - worthy.size(), fresh, published);
        } catch (RuntimeException e) {
            Integer status = e instanceof FetchException fe ? fe.httpStatus() : null;
            runs.finish(run, "FAILED", status, 0, 0, e.getMessage());
            if (sources.recordFailure(src.id(), String.valueOf(e.getMessage()))) {
                log.warn("Source {} disabled after {} consecutive failures", src.name(), FeedSourceRepository.DISABLE_AFTER_FAILURES);
            }
            throw e;
        }
    }
}
