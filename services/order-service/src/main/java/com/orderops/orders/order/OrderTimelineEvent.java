package com.orderops.orders.order;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Embeddable
public class OrderTimelineEvent {
    @Column(name = "event_id", nullable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(nullable = false)
    private Instant occurredAt;

    protected OrderTimelineEvent() {}

    public OrderTimelineEvent(String type, String message, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.message = message;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
