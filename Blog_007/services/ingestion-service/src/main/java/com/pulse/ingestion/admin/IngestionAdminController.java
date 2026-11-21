package com.pulse.ingestion.admin;

import com.pulse.ingestion.fetch.SsrfGuard;
import com.pulse.ingestion.ingest.FetchRunRepository;
import com.pulse.ingestion.queue.DeadLetterRepository;
import com.pulse.ingestion.queue.FetchFeedMessage;
import com.pulse.ingestion.queue.JobPublisher;
import com.pulse.ingestion.source.FeedSource;
import com.pulse.ingestion.source.FeedSourceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin API for feeds (PLAN §12.2). Served by the scheduler role. Requires ROLE_ADMIN + CSRF for writes. */
@RestController
@RequestMapping("/api/v1/admin")
@Profile("scheduler")
public class IngestionAdminController {

    public record CreateSource(@NotBlank @Size(max = 120) String name,
                               @NotBlank @Size(max = 500) String siteUrl,
                               @NotBlank @Size(max = 500) String feedUrl,
                               @Pattern(regexp = "TECH|FOOTBALL|AI") String section,
                               @Min(15) @Max(1440) Integer fetchIntervalMin) {
    }

    public record PatchSource(Boolean active, @Min(15) @Max(1440) Integer fetchIntervalMin, @Size(max = 500) String feedUrl) {
    }

    private final FeedSourceRepository sources;
    private final FetchRunRepository runs;
    private final DeadLetterRepository deadLetters;
    private final JobPublisher jobs;
    private final SsrfGuard guard;

    public IngestionAdminController(FeedSourceRepository sources, FetchRunRepository runs, DeadLetterRepository deadLetters,
                                    JobPublisher jobs, SsrfGuard guard) {
        this.sources = sources;
        this.runs = runs;
        this.deadLetters = deadLetters;
        this.jobs = jobs;
        this.guard = guard;
    }

    @GetMapping("/sources")
    public List<FeedSource> list() {
        return sources.findAll();
    }

    @PostMapping("/sources")
    public ResponseEntity<FeedSource> create(@Valid @RequestBody CreateSource req) {
        guard.checkUrl(req.feedUrl());                    // reject file:/, internal ports … before saving
        guard.checkUrl(req.siteUrl());
        UUID id = sources.create(req.name(), req.siteUrl(), req.feedUrl(), req.section() == null ? "TECH" : req.section(),
                req.fetchIntervalMin() == null ? 30 : req.fetchIntervalMin());
        return ResponseEntity.status(HttpStatus.CREATED).body(sources.findById(id).orElseThrow());
    }

    @PatchMapping("/sources/{id}")
    public FeedSource patch(@PathVariable UUID id, @Valid @RequestBody PatchSource req) {
        if (req.feedUrl() != null) {
            guard.checkUrl(req.feedUrl());
        }
        sources.update(id, req.active(), req.fetchIntervalMin(), req.feedUrl());
        return sources.findById(id).orElseThrow(() -> new IllegalArgumentException("No source " + id));
    }

    @PostMapping("/sources/{id}/fetch-now")
    public ResponseEntity<Map<String, Object>> fetchNow(@PathVariable UUID id) {
        requireActive(sources.findById(id).orElseThrow(() -> new IllegalArgumentException("No source " + id)));
        FetchFeedMessage job = FetchFeedMessage.first(id);
        jobs.enqueue(job);
        return ResponseEntity.accepted().body(Map.of("messageId", job.messageId(), "status", "queued"));
    }

    @GetMapping("/ingestion/runs")
    public List<FetchRunRepository.FetchRun> runs() {
        return runs.recent(100);
    }

    @GetMapping("/ingestion/dlq")
    public List<DeadLetterRepository.DeadLetter> dlq() {
        return deadLetters.recent();
    }

    /** Give a dead-lettered job a fresh start (attempt 1) — e.g. after fixing the feed URL. */
    @PostMapping("/ingestion/dlq/{id}/requeue")
    public ResponseEntity<Map<String, Object>> requeue(@PathVariable UUID id) {
        deadLetters.sourceOf(id).flatMap(sources::findById).ifPresent(IngestionAdminController::requireActive);
        UUID sourceId = deadLetters.markRequeued(id).orElseThrow(() -> new IllegalArgumentException("Unknown or already requeued: " + id));
        FetchFeedMessage job = FetchFeedMessage.first(sourceId);
        jobs.enqueue(job);
        return ResponseEntity.accepted().body(Map.of("messageId", job.messageId(), "status", "requeued"));
    }

    /**
     * The worker silently skips paused sources (correct: "paused" must win over a job already in the queue).
     * So refuse here instead of answering 202 for a job that would never run.
     */
    private static void requireActive(FeedSource src) {
        if (!src.active()) {
            throw new IllegalStateException("Source '" + src.name() + "' is paused; set active=true first");
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail paused(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail notFound(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(com.pulse.ingestion.fetch.FetchException.class)
    ProblemDetail badUrl(com.pulse.ingestion.fetch.FetchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A source with this feed URL already exists");
    }
}
