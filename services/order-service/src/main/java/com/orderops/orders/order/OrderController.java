package com.orderops.orders.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "orders", description = "Create, inspect and recover stock-backed orders")
public class OrderController {
    private final OrderApplicationService orders;

    public OrderController(OrderApplicationService orders) {
        this.orders = orders;
    }

    @Operation(summary = "Create a confirmed order", description = "Operator only. Reserves stock and persists an outgoing event atomically. Reordered SKU lines compare canonically; a changed payload under the same key is rejected.")
    @ApiResponse(responseCode = "201", description = "Order confirmed and stock reserved", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "created", value = ORDER_EXAMPLE)))
    @ApiResponse(responseCode = "200", description = "Identical request replay; returns the existing order without reserving stock again", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "replay", value = ORDER_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "Invalid request; see Problem Details")
    @ApiResponse(responseCode = "409", description = "Insufficient stock or conflicting key reuse")
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Parameter(description = "Client-chosen idempotency key, scoped to the authenticated actor", example = "demo-create-0001") @RequestHeader("Idempotency-Key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Order to reserve", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "request", value = ORDER_REQUEST_EXAMPLE))) @Valid @RequestBody CreateOrderRequest request,
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

    @Operation(summary = "Recover a failed order", description = "Operator only. A FULFILLMENT_FAILED order returns to CONFIRMED in a new attempt generation; the carrier key remains the order UUID.")
    @ApiResponse(responseCode = "202", description = "Recovery scheduled durably", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "recovering", value = RETRY_EXAMPLE)))
    @ApiResponse(responseCode = "200", description = "Replay of a previously accepted retry", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "replay", value = RETRY_EXAMPLE)))
    @ApiResponse(responseCode = "409", description = "Order cannot be retried in its current state or the key conflicts")
    @PostMapping("/{id}/retry")
    public ResponseEntity<OrderResponse> retry(
            @Parameter(description = "Order identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") @PathVariable UUID id,
            @Parameter(description = "Client-chosen idempotency key", example = "demo-retry-0001") @RequestHeader("Idempotency-Key") String key,
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

    @Operation(summary = "Read an order", description = "Returns order details, status and durable timeline.")
    @ApiResponse(responseCode = "200", description = "Order details", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "order", value = ORDER_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "Order does not exist")
    @GetMapping("/{id}")
    public OrderResponse get(
            @Parameter(description = "Order identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") @PathVariable UUID id) {
        return orders.get(id);
    }

    @Operation(summary = "List orders", description = "Orders newest first, paginated.")
    @ApiResponse(responseCode = "200", description = "Order page", content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "page", value = PAGE_EXAMPLE)))
    @GetMapping
    public OrderApplicationService.OrderPage list(
            @Parameter(description = "Zero-based page number", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size between 1 and 100", example = "20") @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException(
                    "Page must be nonnegative and size between 1 and 100");
        return orders.list(page, size);
    }

    private static final String ORDER_REQUEST_EXAMPLE = """
            {
              "customerReference": "DEMO-1",
              "shippingAddress": {
                "recipient": "Ada Lovelace",
                "line1": "10 Example Street",
                "city": "London",
                "postalCode": "SW1A 1AA",
                "country": "GB"
              },
              "demoScenario": "NORMAL",
              "items": [ { "sku": "COFFEE", "quantity": 1 } ]
            }
            """;

    private static final String ORDER_EXAMPLE = """
            {
              "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "customerReference": "DEMO-1",
              "status": "CONFIRMED",
              "createdAt": "2026-11-03T10:00:00Z",
              "shippingAddress": {
                "recipient": "Ada Lovelace",
                "line1": "10 Example Street",
                "city": "London",
                "postalCode": "SW1A 1AA",
                "country": "GB"
              },
              "demoScenario": "NORMAL",
              "attemptGeneration": 1,
              "shipmentId": null,
              "trackingNumber": null,
              "items": [ { "sku": "COFFEE", "quantity": 1 } ],
              "timeline": [
                {
                  "id": "4fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "type": "OrderConfirmed",
                  "message": "Order confirmed and stock reserved.",
                  "occurredAt": "2026-11-03T10:00:00Z"
                }
              ]
            }
            """;

    private static final String RETRY_EXAMPLE = """
            {
              "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "customerReference": "DEMO-1",
              "status": "CONFIRMED",
              "createdAt": "2026-11-03T10:00:00Z",
              "shippingAddress": {
                "recipient": "Ada Lovelace",
                "line1": "10 Example Street",
                "city": "London",
                "postalCode": "SW1A 1AA",
                "country": "GB"
              },
              "demoScenario": "FAIL_THEN_RECOVER",
              "attemptGeneration": 2,
              "shipmentId": null,
              "trackingNumber": null,
              "items": [ { "sku": "COFFEE", "quantity": 1 } ],
              "timeline": [
                {
                  "id": "4fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "type": "RetryRequested",
                  "message": "Recovery requested for a failed fulfillment.",
                  "occurredAt": "2026-11-03T10:05:00Z"
                }
              ]
            }
            """;

    private static final String PAGE_EXAMPLE = """
            {
              "content": [
                {
                  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "customerReference": "DEMO-1",
                  "status": "SHIPMENT_CREATED",
                  "createdAt": "2026-11-03T10:00:00Z",
                  "shippingAddress": { "recipient": "Ada Lovelace", "line1": "10 Example Street", "city": "London", "postalCode": "SW1A 1AA", "country": "GB" },
                  "demoScenario": "NORMAL",
                  "attemptGeneration": 1,
                  "shipmentId": "SHP-0001",
                  "trackingNumber": "TRK-0001",
                  "items": [ { "sku": "COFFEE", "quantity": 1 } ],
                  "timeline": [ { "id": "4fa85f64-5717-4562-b3fc-2c963f66afa6", "type": "ShipmentCreated", "message": "Carrier accepted the shipment.", "occurredAt": "2026-11-03T10:00:10Z" } ]
                }
              ],
              "number": 0,
              "size": 20,
              "totalElements": 1,
              "totalPages": 1
            }
            """;
}
