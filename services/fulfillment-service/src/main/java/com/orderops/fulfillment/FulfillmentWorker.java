package com.orderops.fulfillment;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "orderops.fulfillment.worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
class FulfillmentWorker {
    private final FulfillmentStore store;
    private final CarrierClient carrier;

    FulfillmentWorker(FulfillmentStore store, CarrierClient carrier) {
        this.store = store;
        this.carrier = carrier;
    }

    @Scheduled(fixedDelayString = "${orderops.fulfillment.poll-ms:250}")
    void tick() {
        store.claim()
                .ifPresent(
                        job -> {
                            try (var orderContext =
                                            MDC.putCloseable(
                                                    "orderId", job.command().orderId().toString());
                                    var eventContext =
                                            MDC.putCloseable(
                                                    "eventId",
                                                    job.command().eventId().toString())) {
                                var outcome = carrier.create(job.command());
                                store.complete(job, outcome);
                                LoggerFactory.getLogger(FulfillmentWorker.class)
                                        .atInfo()
                                        .addKeyValue("generation", job.command().generation())
                                        .addKeyValue("attempt", job.attempts())
                                        .addKeyValue("carrierAccepted", outcome.success())
                                        .addKeyValue("retryable", outcome.retryable())
                                        .log("Fulfillment attempt processed");
                            }
                        });
    }
}
