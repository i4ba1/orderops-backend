package com.orderops.orders.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerReference,
        OrderStatus status,
        Instant createdAt,
        ShippingAddress shippingAddress,
        DemoScenario demoScenario,
        int attemptGeneration,
        String shipmentId,
        String trackingNumber,
        List<Item> items,
        List<TimelineEntry> timeline) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerReference(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getShippingAddress(),
                order.getDemoScenario(),
                order.getAttemptGeneration(),
                order.getShipmentId(),
                order.getTrackingNumber(),
                order.getItems().stream().map(i -> new Item(i.getSku(), i.getQuantity())).toList(),
                order.getTimeline().stream()
                        .map(
                                e ->
                                        new TimelineEntry(
                                                e.getId(),
                                                e.getType(),
                                                e.getMessage(),
                                                e.getOccurredAt()))
                        .toList());
    }

    public record Item(String sku, int quantity) {}

    public record TimelineEntry(UUID id, String type, String message, Instant occurredAt) {}
}
