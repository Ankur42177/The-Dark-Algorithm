package com.pulse.ingestion.queue;

import com.pulse.ingestion.fetch.FetchException;
import com.pulse.ingestion.ingest.IngestService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.AmqpException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit test of the worker's ack/nack decisions with mocks (no RabbitMQ, no database).
 * The key case comes from the Phase 7 code review: if scheduling the retry ITSELF fails, the message must be
 * NACKed and requeued — not left unacked forever, blocking a prefetch slot.
 */
class FeedFetchWorkerTest {

    private final IngestService ingest = mock(IngestService.class);
    private final JobPublisher jobs = mock(JobPublisher.class);
    private final DeadLetterRepository deadLetters = mock(DeadLetterRepository.class);
    private final Channel channel = mock(Channel.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final FeedFetchWorker worker = new FeedFetchWorker(ingest, jobs, deadLetters, json, new SimpleMeterRegistry());

    @Test
    void success_isAcked() throws Exception {
        worker.onJob(message(1), channel);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void transientFailure_isRetriedLaterThenAcked() throws Exception {
        doThrow(new IllegalStateException("timeout")).when(ingest).process(any());
        worker.onJob(message(1), channel);
        verify(jobs).retryLater(any());
        verify(channel).basicAck(7L, false);
    }

    @Test
    void whenTheRetryCannotBePublished_theJobIsNackedAndRequeued() throws Exception {
        doThrow(new IllegalStateException("timeout")).when(ingest).process(any());
        doThrow(new AmqpException("broker gone")).when(jobs).retryLater(any());

        worker.onJob(message(1), channel);

        verify(channel).basicNack(7L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void whenTheDlqPublishFails_noDeadLetterRowIsWritten() throws Exception {
        doThrow(FetchException.permanent("HTTP 404")).when(ingest).process(any());
        doThrow(new AmqpException("broker gone")).when(jobs).deadLetter(any());

        worker.onJob(message(1), channel);

        verify(deadLetters, never()).save(any(), anyString());   // published first, recorded second
        verify(channel).basicNack(7L, false, true);
    }

    private Message message(int attempt) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(7L);
        byte[] body = json.writeValueAsBytes(new FetchFeedMessage(UUID.randomUUID(), UUID.randomUUID(), attempt, java.time.Instant.now()));
        return new Message(body, props);
    }
}
