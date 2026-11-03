package com.orderops.orders.messaging;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.orders.order.*;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Testcontainers
@TestPropertySource(
        properties = {
            "orderops.security.operator-password=test-operator-password",
            "orderops.security.viewer-password=test-viewer-password",
            "orderops.messaging.publish-enabled=false"
        })
class KafkaOrderMessagingIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired OrderApplicationService orders;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void isolateOutbox() {
        jdbc.update("DELETE FROM outbox_events");
    }

    private OrderResponse create() {
        jdbc.update(
                "INSERT INTO inventory_items(sku,name,available_quantity) VALUES"
                        + " ('COFFEE','Coffee',100) ON CONFLICT(sku) DO NOTHING");
        return orders.create(
                        "operator",
                        UUID.randomUUID().toString(),
                        new CreateOrderRequest(
                                "KAFKA",
                                new ShippingAddress("Ada", "Street", "London", "123", "GB"),
                                DemoScenario.NORMAL,
                                List.of(new CreateOrderRequest.Item("COFFEE", 1))))
                .order();
    }

    @Test
    void acknowledgedOutboxMessagesReachKafkaAndPublishBeforeMarkCanReplay() throws Exception {
        var order = create();
        var sent = new ArrayList<String>();
        try (var consumer = consumer("orderops.orders.v1")) {
            assertThatThrownBy(
                            () ->
                                    dispatcher.publishBatch(
                                            (key, json) -> {
                                                send("orderops.orders.v1", key, json);
                                                sent.add(json);
                                                throw new IllegalStateException(
                                                        "Crash before marking published");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT published_at IS NULL FROM outbox_events WHERE"
                                            + " aggregate_id=?",
                                    Boolean.class,
                                    order.id()))
                    .isTrue();
            dispatcher.publishBatch(
                    (key, json) -> {
                        send("orderops.orders.v1", key, json);
                        sent.add(json);
                    });
            assertThat(sent).hasSizeGreaterThanOrEqualTo(2);
            var records = readMatching(consumer, order.id().toString(), 2);
            assertThat(records).hasSize(2);
            assertThat(mapper.readTree(records.get(0)).path("eventId").asText())
                    .isEqualTo(mapper.readTree(records.get(1)).path("eventId").asText());
            assertThat(
                            mapper.readTree(records.get(0))
                                    .at("/payload/shippingAddress/country")
                                    .asText())
                    .isEqualTo("GB");
        }
    }

    @Test
    void poisonRecordsReachTheDeadLetterTopicBeforeFollowingValidResultsAreApplied()
            throws Exception {
        var order = create();
        var id = order.id().toString();
        try (var dlt = consumer("orderops.fulfillment.v1.DLT")) {
            kafka.send("orderops.fulfillment.v1", 0, id, "{broken-json").get(10, TimeUnit.SECONDS);
            var valid =
                    mapper.writeValueAsString(
                            Map.of(
                                    "eventId",
                                    UUID.randomUUID().toString(),
                                    "orderId",
                                    id,
                                    "eventType",
                                    "ShipmentCreated",
                                    "schemaVersion",
                                    1,
                                    "occurredAt",
                                    Instant.now().toString(),
                                    "payload",
                                    Map.of(
                                            "attemptGeneration",
                                            1,
                                            "shipmentId",
                                            "SHP-KAFKA",
                                            "trackingNumber",
                                            "TRACK-KAFKA",
                                            "message",
                                            "Accepted")));
            kafka.send("orderops.fulfillment.v1", 0, id, valid).get(10, TimeUnit.SECONDS);
            kafka.send("orderops.fulfillment.v1", 0, id, valid).get(10, TimeUnit.SECONDS);
            assertThat(readMatching(dlt, id, 1)).containsExactly("{broken-json");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline
                    && orders.get(order.id()).status() != OrderStatus.SHIPMENT_CREATED)
                Thread.sleep(25);
            assertThat(orders.get(order.id()).shipmentId()).isEqualTo("SHP-KAFKA");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM consumed_fulfillment_events WHERE"
                                            + " order_id=?",
                                    Integer.class,
                                    order.id()))
                    .isEqualTo(1);
            assertThat(orders.get(order.id()).timeline()).hasSize(2);
        }
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "assert-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var consumer =
                new KafkaConsumer<String, String>(
                        properties, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static List<String> readMatching(
            KafkaConsumer<String, String> consumer, String key, int count) {
        var values = new ArrayList<String>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (values.size() < count && System.nanoTime() < deadline)
            for (var record : consumer.poll(Duration.ofMillis(200)))
                if (key.equals(record.key())) {
                    if (record.topic().endsWith(".DLT")) {
                        assertThat(record.headers().lastHeader("orderops-error-reason"))
                                .isNotNull();
                        assertThat(record.headers().lastHeader("orderops-source-offset"))
                                .isNotNull();
                    }
                    values.add(record.value());
                }
        return values;
    }

    private void send(String topic, String key, String value) {
        try {
            kafka.send(topic, key, value).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
