package com.orderops.orders.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "orderops.messaging.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KafkaFulfillmentListener {
    private final FulfillmentProjection projection;
    private final ObjectMapper mapper;

    public KafkaFulfillmentListener(FulfillmentProjection projection, ObjectMapper mapper) {
        this.projection = projection;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${orderops.messaging.fulfillment-topic:orderops.fulfillment.v1}",
            groupId = "orderops-order-results-v1")
    public void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        var result = FulfillmentResult.parse(mapper, record.value());
        if (!result.orderId().toString().equals(record.key()))
            throw new InvalidEventException("Kafka key and order identifier differ");
        try (var orderContext = MDC.putCloseable("orderId", result.orderId().toString());
                var eventContext = MDC.putCloseable("eventId", result.eventId().toString())) {
            projection.accept(result);
            acknowledgment.acknowledge();
            LoggerFactory.getLogger(KafkaFulfillmentListener.class)
                    .atInfo()
                    .addKeyValue("generation", result.generation())
                    .log("Fulfillment result consumed");
        }
    }
}
