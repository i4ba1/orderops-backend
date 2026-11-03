package com.orderops.orders.messaging;

import com.orderops.orders.order.OrderRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentProjection {
    private final OrderRepository orders;
    private final JdbcTemplate jdbc;

    public FulfillmentProjection(OrderRepository orders, JdbcTemplate jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    @Transactional
    public void accept(FulfillmentResult result) {
        var order =
                orders.findForUpdate(result.orderId())
                        .orElseThrow(
                                () -> new InvalidEventException("Unknown result order identifier"));
        int inserted =
                jdbc.update(
                        "INSERT INTO consumed_fulfillment_events(event_id,order_id) VALUES (?,?) ON"
                                + " CONFLICT(event_id) DO NOTHING",
                        result.eventId(),
                        result.orderId());
        if (inserted == 0) return;
        if (result.type().equals("ShipmentCreated"))
            order.shipmentCreated(
                    result.generation(),
                    result.shipmentId(),
                    result.trackingNumber(),
                    result.occurredAt());
        else order.fulfillmentFailed(result.generation(), result.message(), result.occurredAt());
        // Managed order changes and the inbox marker commit together, before the listener
        // acknowledges.
    }
}
