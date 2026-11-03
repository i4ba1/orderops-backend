package com.orderops.orders.messaging;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = {"orderops.messaging.enabled", "orderops.messaging.publish-enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class KafkaOutboxScheduler {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxScheduler.class);
    private final OutboxDispatcher dispatcher;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaOutboxScheduler(
            OutboxDispatcher dispatcher,
            KafkaTemplate<String, String> kafka,
            @Value("${orderops.messaging.orders-topic:orderops.orders.v1}") String topic) {
        this.dispatcher = dispatcher;
        this.kafka = kafka;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${orderops.messaging.publish-delay-ms:500}")
    public void publish() {
        try {
            dispatcher.publishBatch(
                    (key, json) -> {
                        try {
                            kafka.send(topic, key, json).get(2, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Outbox publication interrupted");
                        } catch (Exception unavailable) {
                            throw new IllegalStateException(
                                    "Outbox publication was not acknowledged");
                        }
                    });
        } catch (RuntimeException unavailable) {
            log.warn("Outbox publication failed; durable rows retained for retry");
        }
    }
}
