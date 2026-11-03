package com.orderops.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.orders.ApiSession;
import com.orderops.orders.inventory.InsufficientStockException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(
        properties = {
            "orderops.security.operator-password=test-operator-password",
            "orderops.security.viewer-password=test-viewer-password",
            "orderops.messaging.enabled=false"
        })
class OrderApiIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderApplicationService orders;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.orderops.orders.messaging.FulfillmentProjection projection;
    @Autowired com.orderops.orders.messaging.OutboxDispatcher dispatcher;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void seedDedicatedTestDatabase() {
        jdbc.update("DELETE FROM consumed_fulfillment_events");
        jdbc.update("DELETE FROM idempotency_claims");
        jdbc.update("DELETE FROM order_timeline");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM stock_reservations");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM customer_orders");
        jdbc.update("DELETE FROM inventory_items");
        jdbc.update(
                "INSERT INTO inventory_items(sku, name, available_quantity) VALUES"
                        + " ('COFFEE','Coffee',3),('TEA','Tea',1)");
    }

    @Test
    void creatingAnOrderPersistsItsReservationAndOutgoingEventTogether() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        var result =
                mvc.perform(
                                post("/api/orders")
                                        .session(login.session())
                                        .header("Idempotency-Key", "request-1234")
                                        .header("X-CSRF-TOKEN", login.csrfToken())
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                """
                        {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[{"sku":"COFFEE","quantity":2}]}
                        """))
                        .andExpect(status().isCreated())
                        .andReturn();
        var id = mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/orders/" + id).session(login.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='COFFEE'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload->>'eventType' FROM outbox_events", String.class))
                .isEqualTo("OrderConfirmed");
    }

    @Test
    void aFailureOnTheLastLineRollsBackEveryReservation() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                        {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[{"sku":"COFFEE","quantity":1},{"sku":"TEA","quantity":2}]}
                        """))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='COFFEE'",
                                Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isZero();
    }

    @Test
    void duplicateSkusCannotChangeStock() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                        {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[{"sku":"COFFEE","quantity":1},{"sku":"COFFEE","quantity":1}]}
                        """))
                .andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='COFFEE'",
                                Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isZero();
    }

    @Test
    void anUnknownLastSkuRollsBackAnEarlierReservation() throws Exception {
        var login = ApiSession.login(mvc, mapper, "operator");
        mvc.perform(
                        post("/api/orders")
                                .session(login.session())
                                .header("Idempotency-Key", "request-1234")
                                .header("X-CSRF-TOKEN", login.csrfToken())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                        {"customerReference":"CUSTOMER-42","shippingAddress":{"recipient":"Ada","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"items":[{"sku":"COFFEE","quantity":1},{"sku":"UNKNOWN","quantity":1}]}
                        """))
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='COFFEE'",
                                Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_claims", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isZero();
    }

    @Test
    void onlyOneConcurrentBuyerCanReserveTheLastUnit() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> buyLastTea(start, "FIRST"));
            var second = executor.submit(() -> buyLastTea(start, "SECOND"));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "conflict");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='TEA'",
                                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentIdenticalKeysReserveOnlyOnceAndDifferentPayloadsConflict() throws Exception {
        var start = new CountDownLatch(1);
        var request =
                new CreateOrderRequest(
                        "SAME-KEY",
                        OrderLifecycleTest.address(),
                        DemoScenario.NORMAL,
                        List.of(
                                new CreateOrderRequest.Item("TEA", 1),
                                new CreateOrderRequest.Item("COFFEE", 1)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return orders.create("operator", "same-key-123", request);
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return orders.create("operator", "same-key-123", request);
                            });
            start.countDown();
            var one = first.get(10, TimeUnit.SECONDS);
            var two = second.get(10, TimeUnit.SECONDS);
            assertThat(one.order().id()).isEqualTo(two.order().id());
            assertThat(List.of(one.replayed(), two.replayed()))
                    .containsExactlyInAnyOrder(false, true);
        }
        var reversed =
                new CreateOrderRequest(
                        "SAME-KEY",
                        OrderLifecycleTest.address(),
                        DemoScenario.NORMAL,
                        List.of(
                                new CreateOrderRequest.Item("COFFEE", 1),
                                new CreateOrderRequest.Item("TEA", 1)));
        assertThat(orders.create("operator", "same-key-123", reversed).replayed()).isTrue();
        var changed =
                new CreateOrderRequest(
                        "DIFFERENT",
                        request.shippingAddress(),
                        request.demoScenario(),
                        request.items());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orders.create("operator", "same-key-123", changed))
                .isInstanceOf(OrderConflictException.class);
        var changedAddress =
                new CreateOrderRequest(
                        request.customerReference(),
                        new ShippingAddress("Grace", "Another Street", "London", "123", "GB"),
                        request.demoScenario(),
                        request.items());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orders.create("operator", "same-key-123", changedAddress))
                .isInstanceOf(OrderConflictException.class);
        var changedScenario =
                new CreateOrderRequest(
                        request.customerReference(),
                        request.shippingAddress(),
                        DemoScenario.FAIL_THEN_RECOVER,
                        request.items());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orders.create("operator", "same-key-123", changedScenario))
                .isInstanceOf(OrderConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='COFFEE'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void identicalKeysBelongToTheActorAndConcurrentRetriesAdvanceOnlyOnce() throws Exception {
        var request =
                new CreateOrderRequest(
                        "ACTOR",
                        OrderLifecycleTest.address(),
                        DemoScenario.NORMAL,
                        List.of(new CreateOrderRequest.Item("COFFEE", 1)));
        var first = orders.create("operator", "actor-request", request).order();
        var second = orders.create("another-operator", "actor-request", request).order();
        assertThat(first.id()).isNotEqualTo(second.id());
        projection.accept(result(first.id(), "FulfillmentFailed", 1, "actor-failure"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one =
                    executor.submit(
                            () -> {
                                start.await();
                                return orders.retry("operator", "retry-shared", first.id());
                            });
            var two =
                    executor.submit(
                            () -> {
                                start.await();
                                return orders.retry("operator", "retry-shared", first.id());
                            });
            start.countDown();
            var oneResult = one.get(10, TimeUnit.SECONDS);
            var twoResult = two.get(10, TimeUnit.SECONDS);
            assertThat(List.of(oneResult.replayed(), twoResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(oneResult.order().attemptGeneration()).isEqualTo(2);
            assertThat(twoResult.order().attemptGeneration()).isEqualTo(2);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=?",
                                Integer.class,
                                first.id()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='COFFEE'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aHeldStockLockBlocksTheBuyerUntilTheCommittedQuantityIsKnown() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder =
                    executor.submit(
                            () ->
                                    new org.springframework.transaction.support.TransactionTemplate(
                                                    transactionManager)
                                            .execute(
                                                    status -> {
                                                        jdbc.queryForObject(
                                                                "SELECT available_quantity FROM"
                                                                        + " inventory_items WHERE"
                                                                        + " sku='TEA' FOR UPDATE",
                                                                Integer.class);
                                                        jdbc.update(
                                                                "UPDATE inventory_items SET"
                                                                    + " available_quantity=0 WHERE"
                                                                    + " sku='TEA'");
                                                        held.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    15, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "Lock release timed out");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                        return null;
                                                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var buyer =
                    executor.submit(
                            () -> {
                                started.countDown();
                                return buyLastTea(new CountDownLatch(0), "BLOCKED");
                            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int waiting;
                do {
                    waiting =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM pg_stat_activity WHERE"
                                        + " datname=current_database() AND wait_event_type='Lock'"
                                        + " AND query LIKE '%inventory_items%'",
                                    Integer.class);
                    if (waiting == 0) Thread.sleep(20);
                } while (waiting == 0 && System.nanoTime() < deadline);
                assertThat(waiting)
                        .as("the buyer is waiting on the real stock row lock")
                        .isGreaterThan(0);
                assertThat(buyer.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
            assertThat(buyer.get(5, TimeUnit.SECONDS)).isEqualTo("conflict");
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isZero();
    }

    @Test
    void retriesAndResultDeduplicationCommitAtomicallyAndIgnoreOldFailures() throws Exception {
        var created =
                orders.create(
                                "operator",
                                "create-12345",
                                new CreateOrderRequest(
                                        "RECOVERY",
                                        OrderLifecycleTest.address(),
                                        DemoScenario.NORMAL,
                                        List.of(new CreateOrderRequest.Item("COFFEE", 1))))
                        .order();
        var failure = result(created.id(), "FulfillmentFailed", 1, "failure-1");
        projection.accept(failure);
        projection.accept(failure);
        assertThat(orders.get(created.id()).timeline()).hasSize(2);
        var retry = orders.retry("operator", "retry-12345", created.id());
        assertThat(retry.order().attemptGeneration()).isEqualTo(2);
        assertThat(orders.retry("operator", "retry-12345", created.id()).replayed()).isTrue();
        projection.accept(result(created.id(), "FulfillmentFailed", 1, "stale-1"));
        projection.accept(result(created.id(), "ShipmentCreated", 2, "success-2"));
        projection.accept(result(created.id(), "FulfillmentFailed", 2, "late-failure"));
        var completed = orders.get(created.id());
        assertThat(completed.status()).isEqualTo(OrderStatus.SHIPMENT_CREATED);
        assertThat(completed.shipmentId()).isEqualTo("SHP-1");
        assertThat(completed.timeline()).hasSize(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM consumed_fulfillment_events", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM inventory_items WHERE sku='COFFEE'",
                                Integer.class))
                .isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orders.retry("operator", "another-retry", created.id()))
                .isInstanceOf(OrderConflictException.class);
    }

    @Test
    void publishBeforeMarkFailureRetainsTheRowAndReplaysTheSameEnvelopeId() {
        orders.create(
                "operator",
                "outbox-12345",
                new CreateOrderRequest(
                        "OUTBOX",
                        OrderLifecycleTest.address(),
                        DemoScenario.NORMAL,
                        List.of(new CreateOrderRequest.Item("COFFEE", 1))));
        var sent = new java.util.ArrayList<String>();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                dispatcher.publishBatch(
                                        (key, payload) -> {
                                            sent.add(payload);
                                            throw new IllegalStateException(
                                                    "Crash after broker received the event");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL",
                                Integer.class))
                .isEqualTo(1);
        dispatcher.publishBatch((key, payload) -> sent.add(payload));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isEqualTo(sent.get(1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NOT NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void listingUsesNewestFirstAndReportsTotalPages() {
        var first =
                orders.create(
                                "operator",
                                "page-first",
                                new CreateOrderRequest(
                                        "FIRST",
                                        OrderLifecycleTest.address(),
                                        DemoScenario.NORMAL,
                                        List.of(new CreateOrderRequest.Item("COFFEE", 1))))
                        .order();
        var second =
                orders.create(
                                "operator",
                                "page-second",
                                new CreateOrderRequest(
                                        "SECOND",
                                        OrderLifecycleTest.address(),
                                        DemoScenario.NORMAL,
                                        List.of(new CreateOrderRequest.Item("COFFEE", 1))))
                        .order();
        assertThat(orders.list(0, 1).content())
                .extracting(OrderResponse::id)
                .containsExactly(second.id());
        assertThat(orders.list(1, 1).content())
                .extracting(OrderResponse::id)
                .containsExactly(first.id());
        assertThat(orders.list(0, 1).totalPages()).isEqualTo(2);
        assertThat(orders.list(0, 1).totalElements()).isEqualTo(2);
    }

    private com.orderops.orders.messaging.FulfillmentResult result(
            java.util.UUID id, String type, int generation, String identifier) throws Exception {
        var event =
                java.util.Map.of(
                        "eventId",
                        java.util.UUID.nameUUIDFromBytes(identifier.getBytes()).toString(),
                        "orderId",
                        id.toString(),
                        "eventType",
                        type,
                        "schemaVersion",
                        1,
                        "occurredAt",
                        java.time.Instant.now().toString(),
                        "payload",
                        java.util.Map.of(
                                "attemptGeneration",
                                generation,
                                "shipmentId",
                                "SHP-1",
                                "trackingNumber",
                                "TRACK-1",
                                "message",
                                "Carrier unavailable"));
        return com.orderops.orders.messaging.FulfillmentResult.parse(
                mapper, mapper.writeValueAsString(event));
    }

    private String buyLastTea(CountDownLatch start, String reference) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Buyer barrier timed out");
        }
        try {
            orders.create(
                    "operator",
                    reference + "-request",
                    new CreateOrderRequest(
                            reference,
                            OrderLifecycleTest.address(),
                            DemoScenario.NORMAL,
                            List.of(new CreateOrderRequest.Item("TEA", 1))));
            return "created";
        } catch (InsufficientStockException insufficient) {
            return "conflict";
        }
    }
}
