package com.orderops.orders.order;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "customer_orders")
public class Order {
    @Id private UUID id;

    @Column(nullable = false, length = 120)
    private String customerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Embedded private ShippingAddress shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DemoScenario demoScenario;

    @Column(nullable = false)
    private int attemptGeneration;

    @Column(length = 160)
    private String shipmentId;

    @Column(length = 160)
    private String trackingNumber;

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_position")
    private List<OrderItem> items = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "order_timeline", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "event_position")
    private List<OrderTimelineEvent> timeline = new ArrayList<>();

    protected Order() {}

    public static Order confirm(
            String reference,
            ShippingAddress address,
            DemoScenario scenario,
            List<OrderItem> items) {
        if (reference == null || reference.isBlank() || reference.length() > 120)
            throw new IllegalArgumentException(
                    "Customer reference must contain 1 to 120 characters");
        if (address == null) throw new IllegalArgumentException("Shipping address is required");
        if (items == null || items.isEmpty() || items.size() > 20)
            throw new IllegalArgumentException("An order must contain 1 to 20 items");
        var skus = new HashSet<String>();
        for (var item : items) {
            if (item == null) throw new IllegalArgumentException("Order items cannot be null");
            if (!skus.add(item.getSku()))
                throw new IllegalArgumentException("Duplicate SKU: " + item.getSku());
        }
        var order = new Order();
        order.id = UUID.randomUUID();
        order.customerReference = reference;
        order.shippingAddress = address;
        order.demoScenario = scenario == null ? DemoScenario.NORMAL : scenario;
        order.status = OrderStatus.CONFIRMED;
        order.createdAt = Instant.now();
        order.attemptGeneration = 1;
        order.items = new ArrayList<>(items);
        order.timeline.add(
                new OrderTimelineEvent(
                        "OrderConfirmed",
                        "Stock reserved; fulfillment requested",
                        order.createdAt));
        return order;
    }

    public void retry() {
        if (status != OrderStatus.FULFILLMENT_FAILED)
            throw new OrderConflictException("Only failed fulfillment can be retried");
        if (shippingAddress == null)
            throw new OrderConflictException(
                    "Legacy order has no shipping address and cannot be retried");
        status = OrderStatus.CONFIRMED;
        attemptGeneration++;
        timeline.add(
                new OrderTimelineEvent(
                        "RetryRequested", "Fulfillment retry requested", Instant.now()));
    }

    public void fulfillmentFailed(int generation, String message, Instant at) {
        if (generation != attemptGeneration || status != OrderStatus.CONFIRMED) return;
        status = OrderStatus.FULFILLMENT_FAILED;
        timeline.add(new OrderTimelineEvent("FulfillmentFailed", message, at));
    }

    public void shipmentCreated(int generation, String shipment, String tracking, Instant at) {
        if (generation != attemptGeneration || status == OrderStatus.SHIPMENT_CREATED) return;
        status = OrderStatus.SHIPMENT_CREATED;
        shipmentId = shipment;
        trackingNumber = tracking;
        timeline.add(
                new OrderTimelineEvent("ShipmentCreated", "Carrier accepted shipment request", at));
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public DemoScenario getDemoScenario() {
        return demoScenario;
    }

    public int getAttemptGeneration() {
        return attemptGeneration;
    }

    public String getShipmentId() {
        return shipmentId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    public List<OrderTimelineEvent> getTimeline() {
        return List.copyOf(timeline);
    }
}
