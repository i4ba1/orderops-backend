package com.orderops.fulfillment;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
class KafkaOutboxPublisher {
    private final FulfillmentStore store;
    private final KafkaTemplate<String, String> template;

    KafkaOutboxPublisher(FulfillmentStore store, KafkaTemplate<String, String> template) {
        this.store = store;
        this.template = template;
    }

    @Scheduled(fixedDelayString = "${orderops.fulfillment.outbox-poll-ms:250}")
    void publish() {
        store.publish(
                (key, value) -> {
                    try {
                        template.send(KafkaConfiguration.RESULTS, key, value)
                                .get(12, TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Outbox publish interrupted", failure);
                    } catch (Exception failure) {
                        throw new IllegalStateException(
                                "Outbox publish was not acknowledged", failure);
                    }
                });
    }
}
