package com.orderops.orders.messaging;

import com.orderops.orders.order.Order;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false)
    private int schemaVersion;

    @Column(nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    private Instant publishedAt;

    @Column(length = 160)
    private String quarantineReason;

    protected OutboxEvent() {}

    public static OutboxEvent orderConfirmed(Order order) {
        return command(order, "OrderConfirmed");
    }

    public static OutboxEvent retryRequested(Order order) {
        return command(order, "RetryRequested");
    }

    private static OutboxEvent command(Order order, String type) {
        if (order.getShippingAddress() == null)
            throw new IllegalArgumentException("Cannot publish an order without shipping details");
        var e = new OutboxEvent();
        e.id = UUID.randomUUID();
        e.aggregateId = order.getId();
        e.eventType = type;
        e.schemaVersion = 1;
        e.occurredAt = Instant.now();
        var a = order.getShippingAddress();
        e.payload =
                Map.of(
                        "eventId",
                        e.id.toString(),
                        "orderId",
                        e.aggregateId.toString(),
                        "eventType",
                        type,
                        "schemaVersion",
                        1,
                        "occurredAt",
                        e.occurredAt.toString(),
                        "payload",
                        Map.of(
                                "attemptGeneration",
                                order.getAttemptGeneration(),
                                "customerReference",
                                order.getCustomerReference(),
                                "shippingAddress",
                                Map.of(
                                        "recipient",
                                        a.recipient(),
                                        "line1",
                                        a.line1(),
                                        "city",
                                        a.city(),
                                        "postalCode",
                                        a.postalCode(),
                                        "country",
                                        a.country()),
                                "demoScenario",
                                order.getDemoScenario().name(),
                                "items",
                                order.getItems().stream()
                                        .map(
                                                i ->
                                                        Map.of(
                                                                "sku",
                                                                i.getSku(),
                                                                "quantity",
                                                                i.getQuantity()))
                                        .toList()));
        return e;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void markPublished() {
        publishedAt = Instant.now();
    }
}
