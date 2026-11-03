package com.orderops.orders.order;

import static org.assertj.core.api.Assertions.*;

import com.orderops.orders.messaging.OutboxEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class OrderLifecycleTest {
    static ShippingAddress address() {
        return new ShippingAddress("Ada Lovelace", "10 Example Street", "London", "SW1A 1AA", "GB");
    }

    static Order confirmed() {
        return Order.confirm(
                "DEMO-1", address(), DemoScenario.NORMAL, List.of(new OrderItem("COFFEE", 2)));
    }

    @Test
    void retryPreservesStockRequestAndShippingWhileAdvancingTheGeneration() {
        var order = confirmed();
        order.fulfillmentFailed(1, "Carrier unavailable", Instant.parse("2026-11-02T00:00:01Z"));
        order.retry();
        var response = OrderResponse.from(order);
        assertThat(response.attemptGeneration()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.shippingAddress()).isEqualTo(address());
        assertThat(response.timeline())
                .extracting(OrderResponse.TimelineEntry::type)
                .containsExactly("OrderConfirmed", "FulfillmentFailed", "RetryRequested");
        assertThatThrownBy(order::retry).isInstanceOf(OrderConflictException.class);
    }

    @Test
    void staleResultsAndLateFailuresCannotOverwriteAShipment() {
        var order = confirmed();
        order.fulfillmentFailed(1, "Unavailable", Instant.now());
        order.retry();
        order.fulfillmentFailed(1, "Old failure", Instant.now());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        order.shipmentCreated(2, "SHP-1", "TRACK-1", Instant.now());
        order.fulfillmentFailed(2, "Late failure", Instant.now());
        order.shipmentCreated(1, "WRONG", "WRONG", Instant.now());
        assertThat(OrderResponse.from(order).shipmentId()).isEqualTo("SHP-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPMENT_CREATED);
        assertThat(order.getTimeline()).hasSize(4);
    }

    @Test
    void theCommandEnvelopeContainsTheCompleteCarrierRequestAndGeneration() {
        var order = confirmed();
        var envelope = OutboxEvent.orderConfirmed(order).getPayload();
        assertThat(envelope)
                .containsEntry("eventType", "OrderConfirmed")
                .containsEntry("schemaVersion", 1)
                .containsEntry("orderId", order.getId().toString());
        var payload = (java.util.Map<?, ?>) envelope.get("payload");
        assertThat(payload.get("demoScenario")).isEqualTo("NORMAL");
        assertThat(payload.get("attemptGeneration")).isEqualTo(1);
        assertThat((java.util.Map<?, ?>) payload.get("shippingAddress")).isNotEmpty();
    }

    @Test
    void domainValidationRejectsInvalidAddresses() {
        assertThatThrownBy(() -> new ShippingAddress(" ", "Street", "City", "123", "GB"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingAddress("Ada", "Street", "City", "123", "gb"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
