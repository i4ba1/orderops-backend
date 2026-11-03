package com.orderops.orders.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDispatcher {
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public OutboxDispatcher(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public int publishBatch(Publisher publisher) {
        var batch = outbox.lockUnpublishedBatch();
        for (var event : batch) {
            try (var orderContext = MDC.putCloseable("orderId", event.getAggregateId().toString());
                    var eventContext = MDC.putCloseable("eventId", event.getId().toString())) {
                publisher.publish(
                        event.getAggregateId().toString(),
                        mapper.writeValueAsString(event.getPayload()));
                LoggerFactory.getLogger(OutboxDispatcher.class)
                        .info("Outbox broker acknowledged event");
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException(
                        "Cannot serialize durable event " + event.getId(), invalid);
            }
            event.markPublished();
        }
        return batch.size();
    }

    @FunctionalInterface
    public interface Publisher {
        void publish(String key, String json);
    }
}
