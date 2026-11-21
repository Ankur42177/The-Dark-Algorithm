package com.pulse.ingestion.queue;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Puts FetchFeed jobs on the exchange (main queue, a retry level, or the DLQ). */
@Component
public class JobPublisher {

    private final RabbitTemplate rabbit;
    private final JsonMapper json;

    public JobPublisher(RabbitTemplate rabbit, JsonMapper json) {
        this.rabbit = rabbit;
        this.json = json;
    }

    public void enqueue(FetchFeedMessage m) {
        send(RabbitTopology.FETCH_KEY, m);
    }

    /** attempt N failed → wait in retry queue N (attempt 1 → 30 s, 2 → 2 min, 3 → 10 min, 4 → 30 min). */
    public void retryLater(FetchFeedMessage failed) {
        send(RabbitTopology.RETRY_QUEUE_PREFIX + failed.attempt(), failed.nextAttempt());
    }

    public void deadLetter(FetchFeedMessage m) {
        send(RabbitTopology.DLQ_KEY, m);
    }

    private void send(String routingKey, FetchFeedMessage m) {
        Message msg = MessageBuilder.withBody(json.writeValueAsBytes(m))
                .setContentType("application/json")
                .setMessageId(m.messageId() + "#" + m.attempt())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)       // written to disk: survives a broker restart
                .build();
        rabbit.send(RabbitTopology.EXCHANGE, routingKey, msg);
    }
}
