package com.orderops.fulfillment;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Disposable real broker plus PostgreSQL; fails visibly when Docker is unavailable. */
@Testcontainers
@SpringBootTest(
        properties = {
            "orderops.fulfillment.worker-enabled=false",
            "spring.kafka.consumer.group-id=fulfillment-integration"
        })
class KafkaTransportIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container static final KafkaContainer kafkaBroker = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.kafka.bootstrap-servers", kafkaBroker::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired FulfillmentStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void duplicatesCommitOnceAndPoisonIsDurablyPublishedToDeadLetterTopic() throws Exception {
        try (var results = consumer(KafkaConfiguration.RESULTS);
                var dead = consumer(KafkaConfiguration.ORDERS + ".DLT")) {
            String wire = CommandCodecTest.command(1);
            kafka.send(KafkaConfiguration.ORDERS, CommandCodecTest.ORDER, wire)
                    .get(15, TimeUnit.SECONDS);
            kafka.send(KafkaConfiguration.ORDERS, CommandCodecTest.ORDER, wire)
                    .get(15, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    jdbc.queryForObject(
                                                            "SELECT count(*) FROM"
                                                                + " fulfillment_consumed_events",
                                                            Integer.class))
                                            .isEqualTo(1));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment_jobs", Integer.class))
                    .isEqualTo(1);
            var lease = store.claim().orElseThrow();
            store.complete(lease, CarrierOutcome.accepted("carrier-kafka", "TRACK-KAFKA"));
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var records = results.poll(Duration.ofMillis(500));
                                assertThat(records.count()).isPositive();
                                assertThat(
                                                mapper.readTree(records.iterator().next().value())
                                                        .path("eventType")
                                                        .asText())
                                        .isEqualTo("ShipmentCreated");
                            });
            kafka.send(KafkaConfiguration.ORDERS, CommandCodecTest.ORDER, "{\"legacy\":true}")
                    .get(15, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var records = dead.poll(Duration.ofMillis(500));
                                assertThat(records.count()).isPositive();
                                assertThat(records.iterator().next().value())
                                        .isEqualTo("{\"legacy\":true}");
                            });
        }
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBroker.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var consumer = new KafkaConsumer<String, String>(settings);
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
