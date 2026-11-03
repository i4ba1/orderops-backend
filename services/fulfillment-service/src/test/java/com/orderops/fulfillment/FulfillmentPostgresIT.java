package com.orderops.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;

/** Requires real PostgreSQL; fails visibly when unavailable. Run with Maven verify. */
@Testcontainers
class FulfillmentPostgresIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    JdbcTemplate jdbc;
    FulfillmentStore store;
    final CommandCodec codec = new CommandCodec(new ObjectMapper());

    @BeforeEach
    void database() {
        var source =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "TRUNCATE fulfillment_outbox, fulfillment_shipments, fulfillment_jobs,"
                        + " fulfillment_consumed_events");
        restartStore();
    }

    private void restartStore() {
        store =
                new FulfillmentStore(
                        jdbc,
                        new TransactionTemplate(
                                new DataSourceTransactionManager(jdbc.getDataSource())),
                        codec,
                        new ObjectMapper(),
                        Duration.ofSeconds(5),
                        Duration.ZERO);
    }

    @Test
    void duplicateAndReorderedCommandsCreateOneJobAndShipmentAcrossGenerations() {
        var first = codec.decode(CommandCodecTest.command(1));
        store.consume(first);
        store.consume(first);
        assertThat(count("fulfillment_jobs")).isEqualTo(1);
        var lease = store.claim().orElseThrow();
        store.complete(lease, CarrierOutcome.accepted("ship-1", "track-1"));
        store.consume(codec.decode(CommandCodecTest.command(2)));
        store.consume(codec.decode(CommandCodecTest.command(1)));
        assertThat(count("fulfillment_shipments")).isEqualTo(1);
        assertThat(store.claim()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM fulfillment_outbox WHERE"
                                        + " event_type='ShipmentCreated'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void exhaustionSurvivesRepositoryRestartAndManualRecoveryKeepsCarrierKey() {
        store.consume(codec.decode(CommandCodecTest.command(1)));
        for (int i = 1; i <= 3; i++) {
            restartStore();
            var lease = store.claim().orElseThrow();
            assertThat(lease.attempts()).isEqualTo(i);
            assertThat(lease.command().orderId().toString()).isEqualTo(CommandCodecTest.ORDER);
            store.complete(lease, CarrierOutcome.failed(true, "Carrier returned HTTP 503"));
        }
        assertThat(store.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment_jobs", String.class))
                .isEqualTo("FAILED");
        store.consume(codec.decode(CommandCodecTest.command(2)));
        var retry = store.claim().orElseThrow();
        assertThat(retry.command().generation()).isEqualTo(2);
        assertThat(retry.command().orderId().toString()).isEqualTo(CommandCodecTest.ORDER);
        store.complete(retry, CarrierOutcome.accepted("ship-1", "track-1"));
        assertThat(count("fulfillment_shipments")).isEqualTo(1);
    }

    @Test
    void concurrentCommandsAndClaimsSerializePerOrder() throws Exception {
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var commands = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++)
                commands.add(
                        threads.submit(
                                () -> store.consume(codec.decode(CommandCodecTest.command(1)))));
            for (var task : commands) task.get();
            assertThat(count("fulfillment_jobs")).isEqualTo(1);
            assertThat(count("fulfillment_consumed_events")).isEqualTo(12);
            var first = threads.submit(() -> store.claim());
            var second = threads.submit(() -> store.claim());
            assertThat(
                            java.util.stream.Stream.of(first.get(), second.get())
                                    .filter(java.util.Optional::isPresent)
                                    .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void carrierCallRunsAfterClaimCommitWithoutADatabaseTransaction() {
        store.consume(codec.decode(CommandCodecTest.command(1)));
        var transactionActive = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var carrier =
                new CarrierClient(new ObjectMapper(), "http://localhost:1", Duration.ofSeconds(1)) {
                    @Override
                    CarrierOutcome create(Command command) {
                        transactionActive.set(
                                org.springframework.transaction.support
                                        .TransactionSynchronizationManager
                                        .isActualTransactionActive());
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT attempts FROM fulfillment_jobs",
                                                Integer.class))
                                .isEqualTo(1);
                        return CarrierOutcome.accepted("ship-1", "track-1");
                    }
                };
        new FulfillmentWorker(store, carrier).tick();
        assertThat(transactionActive.get()).isFalse();
        assertThat(count("fulfillment_shipments")).isEqualTo(1);
    }

    @Test
    void changedAddressOnRetryIsRejectedWithoutRecordingConsumption() {
        store.consume(codec.decode(CommandCodecTest.command(1)));
        assertThatThrownBy(
                        () ->
                                store.consume(
                                        codec.decode(
                                                CommandCodecTest.command(2)
                                                        .replace(
                                                                "10 Street", "Different address"))))
                .isInstanceOf(InvalidCommandException.class);
        assertThat(count("fulfillment_consumed_events")).isEqualTo(1);
        assertThat(count("fulfillment_jobs")).isEqualTo(1);
    }

    @Test
    void expiredLeaseCannotFailNewClaimButLateSuccessReconcilesNewGeneration() {
        store.consume(codec.decode(CommandCodecTest.command(1)));
        var expired = store.claim().orElseThrow();
        jdbc.update("UPDATE fulfillment_jobs SET lease_until=now()-interval '1 second'");
        var replacement = store.claim().orElseThrow();
        store.complete(expired, CarrierOutcome.failed(false, "late bad response"));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment_jobs", String.class))
                .isEqualTo("RUNNING");
        store.complete(replacement, CarrierOutcome.failed(false, "Bad address"));
        store.consume(codec.decode(CommandCodecTest.command(2)));
        store.complete(expired, CarrierOutcome.accepted("ship-1", "track-1"));
        assertThat(store.claim()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM fulfillment_jobs WHERE generation=2",
                                String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void outboxRetainsUnacknowledgedResultsAndReplaysAfterPublisherFailure() {
        store.consume(codec.decode(CommandCodecTest.command(1)));
        store.complete(store.claim().orElseThrow(), CarrierOutcome.accepted("ship-1", "track-1"));
        assertThatThrownBy(
                        () ->
                                store.publish(
                                        (key, value) -> {
                                            throw new IllegalStateException("broker down");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM fulfillment_outbox WHERE published_at IS"
                                        + " NULL",
                                Integer.class))
                .isEqualTo(1);
        store.publish((key, value) -> assertThat(key).isEqualTo(CommandCodecTest.ORDER));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM fulfillment_outbox WHERE published_at IS"
                                        + " NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    void retryCommandArrivingBeforeInitialCommandProcessesOnlyTheNewestGeneration() {
        store.consume(codec.decode(CommandCodecTest.command(2)));
        store.consume(codec.decode(CommandCodecTest.command(1)));
        var lease = store.claim().orElseThrow();
        assertThat(lease.command().generation()).isEqualTo(2);
        assertThat(count("fulfillment_jobs")).isEqualTo(1);
        store.complete(lease, CarrierOutcome.accepted("ship-1", "track-1"));
        assertThat(store.claim()).isEmpty();
    }

    @Test
    void expiredThirdLeaseExhaustsDurablyWithoutAFourthCarrierAttempt() {
        store.consume(codec.decode(CommandCodecTest.command(1)));
        for (int i = 1; i <= 3; i++) {
            assertThat(store.claim().orElseThrow().attempts()).isEqualTo(i);
            jdbc.update("UPDATE fulfillment_jobs SET lease_until=now()-interval '1 second'");
            restartStore();
        }
        assertThat(store.claim()).isEmpty();
        assertThat(store.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment_jobs", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM fulfillment_jobs", Integer.class))
                .isEqualTo(3);
        assertThat(count("fulfillment_outbox")).isEqualTo(1);
        store.consume(codec.decode(CommandCodecTest.command(2)));
        assertThat(store.claim().orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    void permanentCarrierBadRequestEmitsFailureImmediately() throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/shipments",
                exchange -> {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                });
        server.start();
        try {
            store.consume(codec.decode(CommandCodecTest.command(1)));
            var client =
                    new CarrierClient(
                            new ObjectMapper(),
                            "http://localhost:" + server.getAddress().getPort(),
                            Duration.ofSeconds(1));
            new FulfillmentWorker(store, client).tick();
            assertThat(jdbc.queryForObject("SELECT status FROM fulfillment_jobs", String.class))
                    .isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT attempts FROM fulfillment_jobs", Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT event_type FROM fulfillment_outbox", String.class))
                    .isEqualTo("FulfillmentFailed");
            assertThat(store.claim()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void operationalMetricsReflectPersistedWork() {
        store.consume(codec.decode(CommandCodecTest.command(1)));
        assertThat(store.metrics()).isEqualTo(new FulfillmentMetrics(1, 0, 0, 0, 0));
        var lease = store.claim().orElseThrow();
        assertThat(store.metrics()).isEqualTo(new FulfillmentMetrics(0, 1, 0, 0, 0));
        store.complete(lease, CarrierOutcome.failed(false, "Carrier returned HTTP 400"));
        assertThat(store.metrics()).isEqualTo(new FulfillmentMetrics(0, 0, 1, 1, 0));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
