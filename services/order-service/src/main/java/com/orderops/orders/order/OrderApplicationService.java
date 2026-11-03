package com.orderops.orders.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.orders.inventory.*;
import com.orderops.orders.messaging.*;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class OrderApplicationService {
    private final OrderRepository orders;
    private final InventoryService inventory;
    private final OutboxRepository outbox;
    private final StockReservationRepository reservations;
    private final IdempotencyStore idempotency;
    private final ObjectMapper mapper;

    public OrderApplicationService(
            OrderRepository orders,
            InventoryService inventory,
            OutboxRepository outbox,
            StockReservationRepository reservations,
            IdempotencyStore idempotency,
            ObjectMapper mapper) {
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
        this.reservations = reservations;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Transactional
    public Mutation create(String actor, String key, CreateOrderRequest request) {
        var canonical =
                new CreateOrderRequest(
                        request.customerReference(),
                        request.shippingAddress(),
                        request.demoScenario(),
                        request.items().stream()
                                .sorted(Comparator.comparing(CreateOrderRequest.Item::sku))
                                .toList());
        var hash = fingerprint(canonical);
        var existing = idempotency.lockAndFind(actor, "create", key);
        if (existing.isPresent()) {
            if (!existing.get().hash().equals(hash))
                throw new OrderConflictException(
                        "Idempotency key was already used for a different order request");
            return new Mutation(get(existing.get().orderId()), true);
        }
        var items =
                canonical.items().stream().map(i -> new OrderItem(i.sku(), i.quantity())).toList();
        var order =
                Order.confirm(
                        canonical.customerReference(),
                        canonical.shippingAddress(),
                        canonical.demoScenario(),
                        items);
        var reserved = inventory.reserve(order);
        orders.save(order);
        reservations.saveAll(reserved);
        outbox.save(OutboxEvent.orderConfirmed(order));
        orders.flush();
        idempotency.claim(actor, "create", key, hash, order.getId());
        return new Mutation(OrderResponse.from(order), false);
    }

    @Transactional
    public Mutation retry(String actor, String key, UUID id) {
        var operation = "retry:" + id;
        var existing = idempotency.lockAndFind(actor, operation, key);
        if (existing.isPresent()) return new Mutation(get(existing.get().orderId()), true);
        var order = orders.findForUpdate(id).orElseThrow(() -> missing(id));
        order.retry();
        outbox.save(OutboxEvent.retryRequested(order));
        orders.flush();
        idempotency.claim(actor, operation, key, fingerprint(id.toString()), id);
        return new Mutation(OrderResponse.from(order), false);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.from(orders.findById(id).orElseThrow(() -> missing(id)));
    }

    @Transactional(readOnly = true)
    public OrderPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException(
                    "Page must be nonnegative and size between 1 and 100");
        var result =
                orders.findAll(
                        PageRequest.of(
                                page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new OrderPage(
                result.getContent().stream().map(OrderResponse::from).toList(),
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages());
    }

    private String fingerprint(Object request) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            mapper.writeValueAsString(request)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint order request", failure);
        }
    }

    private static EntityNotFoundException missing(UUID id) {
        return new EntityNotFoundException("Order not found: " + id);
    }

    public record Mutation(OrderResponse order, boolean replayed) {}

    public record OrderPage(
            List<OrderResponse> content,
            int number,
            int size,
            long totalElements,
            int totalPages) {}
}
