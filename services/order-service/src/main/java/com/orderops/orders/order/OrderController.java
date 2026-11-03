package com.orderops.orders.order;

import jakarta.validation.Valid;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderApplicationService orders;

    public OrderController(OrderApplicationService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateOrderRequest request,
            Principal principal) {
        IdempotencyStore.validateKey(key);
        var result = orders.create(principal.getName(), key, request);
        LoggerFactory.getLogger(OrderController.class)
                .atInfo()
                .addKeyValue("orderId", result.order().id())
                .addKeyValue("replayed", result.replayed())
                .log("Order submission completed");
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .location(URI.create("/api/orders/" + result.order().id()))
                .body(result.order());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<OrderResponse> retry(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            Principal principal) {
        IdempotencyStore.validateKey(key);
        var result = orders.retry(principal.getName(), key, id);
        LoggerFactory.getLogger(OrderController.class)
                .atInfo()
                .addKeyValue("orderId", id)
                .addKeyValue("replayed", result.replayed())
                .log("Fulfillment recovery requested");
        return ResponseEntity.status(result.replayed() ? 200 : 202).body(result.order());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @GetMapping
    public OrderApplicationService.OrderPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException(
                    "Page must be nonnegative and size between 1 and 100");
        return orders.list(page, size);
    }
}
