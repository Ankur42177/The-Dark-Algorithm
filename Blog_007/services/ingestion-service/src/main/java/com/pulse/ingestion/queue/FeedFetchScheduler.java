package com.pulse.ingestion.queue;

import com.pulse.ingestion.source.FeedSourceRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * SCHEDULER role: every minute, find feeds that are due and put one FetchFeed job per feed on RabbitMQ.
 * It does NOT fetch anything itself — that's the workers' job, so fetching scales independently.
 *
 * <p>{@code @SchedulerLock}: with 2 scheduler replicas, both timers fire, but ShedLock lets only one of them
 * run the method (it holds a row lock in the {@code shedlock} table until lockAtMostFor).
 */
@Component
@Profile("scheduler")
public class FeedFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedFetchScheduler.class);

    private final FeedSourceRepository sources;
    private final JobPublisher jobs;

    public FeedFetchScheduler(FeedSourceRepository sources, JobPublisher jobs) {
        this.sources = sources;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${pulse.ingestion.tick:60s}", initialDelayString = "${pulse.ingestion.initial-delay:5s}")
    @SchedulerLock(name = "enqueue-due-feeds", lockAtMostFor = "PT50S", lockAtLeastFor = "PT5S")
    public int enqueueDueFeeds() {
        List<UUID> due = sources.claimDue();
        due.forEach(id -> jobs.enqueue(FetchFeedMessage.first(id)));
        if (!due.isEmpty()) {
            log.info("Enqueued {} feed fetch jobs", due.size());
        }
        return due.size();
    }
}
