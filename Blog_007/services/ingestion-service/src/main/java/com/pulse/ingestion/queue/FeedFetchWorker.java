package com.pulse.ingestion.queue;

import com.pulse.ingestion.fetch.FetchException;
import com.pulse.ingestion.ingest.IngestService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * WORKER role: competing consumers on feed.fetch.q. Run 0..N replicas (KEDA scales them on queue length).
 *
 * <p>MANUAL ACK: RabbitMQ deletes the job only when we say {@code basicAck}. If this process dies mid-job,
 * the unacked message is redelivered to another worker — at-least-once, so processing is idempotent
 * (url_hash UNIQUE + Redis dedupe).
 *
 * <p>Failures: transient (timeout, 5xx, network) → re-published to a delayed retry queue with attempt+1,
 * then the original is acked. After 5 attempts, or on a permanent error (SSRF, 404, not a feed, too big),
 * the job goes to the DLQ and the dead_letters table (admin can requeue it).
 */
@Component
@Profile("worker")
public class FeedFetchWorker {

    private static final Logger log = LoggerFactory.getLogger(FeedFetchWorker.class);

    private final IngestService ingest;
    private final JobPublisher jobs;
    private final DeadLetterRepository deadLetters;
    private final JsonMapper json;
    private final MeterRegistry metrics;

    public FeedFetchWorker(IngestService ingest, JobPublisher jobs, DeadLetterRepository deadLetters, JsonMapper json, MeterRegistry metrics) {
        this.ingest = ingest;
        this.jobs = jobs;
        this.deadLetters = deadLetters;
        this.json = json;
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitTopology.FETCH_QUEUE, ackMode = "MANUAL")
    public void onJob(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        FetchFeedMessage job;
        try {
            job = json.readValue(message.getBody(), FetchFeedMessage.class);
        } catch (RuntimeException e) {
            log.warn("Unreadable job dropped: {}", e.getMessage());
            channel.basicReject(tag, false);                              // poison: don't requeue
            return;
        }
        try {
            ingest.process(job);
            metrics.counter("pulse.ingestion.jobs", "result", "ok").increment();
        } catch (Exception e) {
            try {
                handleFailure(job, e);
            } catch (RuntimeException handlerFailure) {
                // We could neither schedule a retry nor dead-letter the job (RabbitMQ publish or the database
                // failed too). Before this fix the exception escaped: in MANUAL ack mode Spring then neither acks
                // nor nacks, so the message sat "unacked", blocking one of the 10 prefetch slots until the channel
                // closed (Phase 7 code review). NACK + requeue = "give it back, try again" — at-least-once.
                log.warn("Job {}: failure handling failed ({}); requeueing", job.messageId(), handlerFailure.getMessage());
                metrics.counter("pulse.ingestion.jobs", "result", "requeued").increment();
                channel.basicNack(tag, false, true);
                return;
            }
        }
        channel.basicAck(tag, false);                                     // done with THIS delivery either way
    }

    private void handleFailure(FetchFeedMessage job, Exception e) {
        boolean transientFailure = !(e instanceof FetchException fe) || fe.isTransient();
        if (transientFailure && job.attempt() < RabbitTopology.MAX_ATTEMPTS) {
            jobs.retryLater(job);
            metrics.counter("pulse.ingestion.jobs", "result", "retry").increment();
            log.info("Job {} attempt {} failed ({}); retrying later", job.messageId(), job.attempt(), e.getMessage());
        } else {
            // Publish to the DLQ FIRST, then record the row: if the publish fails we requeue (above) without
            // having written a dead_letters row for a job that is about to be processed again.
            jobs.deadLetter(job);
            deadLetters.save(job, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            metrics.counter("pulse.ingestion.jobs", "result", "dlq").increment();
            log.warn("Job {} for source {} dead-lettered after attempt {}: {}", job.messageId(), job.sourceId(), job.attempt(), e.getMessage());
        }
    }
}
