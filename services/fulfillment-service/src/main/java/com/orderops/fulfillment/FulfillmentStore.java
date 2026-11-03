package com.orderops.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** All methods commit before returning. No carrier HTTP is performed in this class. */
@Repository
class FulfillmentStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CommandCodec codec;
    private final ObjectMapper mapper;
    private final Duration leaseDuration;
    private final RetryPolicy retries;

    FulfillmentStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            CommandCodec codec,
            ObjectMapper mapper,
            @Value("${orderops.fulfillment.lease:PT30S}") Duration leaseDuration,
            @Value("${orderops.fulfillment.backoff:PT2S}") Duration backoff) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.codec = codec;
        this.mapper = mapper;
        if (leaseDuration.isZero() || leaseDuration.isNegative() || backoff.isNegative())
            throw new IllegalArgumentException("Invalid fulfillment durations");
        this.leaseDuration = leaseDuration;
        this.retries = new RetryPolicy(backoff);
    }

    void consume(Command command) {
        transactions.executeWithoutResult(
                tx -> {
                    lock(command.orderId());
                    if (jdbc.queryForObject(
                                    "SELECT count(*) FROM fulfillment_consumed_events WHERE"
                                            + " event_id=?",
                                    Integer.class,
                                    command.eventId())
                            > 0) return;
                    var existing =
                            jdbc.query(
                                    "SELECT command_json::text FROM fulfillment_jobs WHERE"
                                            + " order_id=? ORDER BY generation DESC LIMIT 1",
                                    (rs, i) -> codec.decode(rs.getString(1)),
                                    command.orderId());
                    if (!existing.isEmpty()
                            && !identity(existing.getFirst()).equals(identity(command)))
                        throw new InvalidCommandException();
                    jdbc.update(
                            "INSERT INTO fulfillment_consumed_events(event_id,order_id) VALUES"
                                    + " (?,?)",
                            command.eventId(),
                            command.orderId());
                    if (!existing.isEmpty()
                            && existing.getFirst().generation() >= command.generation()) return;
                    jdbc.update(
                            "UPDATE fulfillment_jobs SET status='SUPERSEDED',updated_at=now() WHERE"
                                    + " order_id=? AND status IN ('READY','RUNNING')",
                            command.orderId());
                    jdbc.update(
                            "INSERT INTO fulfillment_jobs(order_id,generation,command_json,status)"
                                    + " VALUES (?,?,?::jsonb,'READY')",
                            command.orderId(),
                            command.generation(),
                            command.wire());
                    var shipments =
                            jdbc.query(
                                    "SELECT shipment_id,tracking_number FROM fulfillment_shipments"
                                            + " WHERE order_id=?",
                                    (rs, i) ->
                                            CarrierOutcome.accepted(
                                                    rs.getString(1), rs.getString(2)),
                                    command.orderId());
                    if (!shipments.isEmpty())
                        success(command.orderId(), command.generation(), shipments.getFirst());
                });
    }

    Optional<JobLease> claim() {
        return transactions.execute(
                tx -> {
                    // Lock order before row everywhere; a nonblocking advisory lock avoids
                    // cross-worker deadlocks.
                    var candidates =
                            jdbc.query(
                                    """
                SELECT order_id FROM fulfillment_jobs
                WHERE (status='READY' AND next_attempt_at<=now()) OR (status='RUNNING' AND lease_until<=now())
                ORDER BY next_attempt_at LIMIT 16
                """,
                                    (rs, i) -> rs.getObject(1, UUID.class));
                    for (UUID order : candidates) {
                        if (!Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT"
                                            + " pg_try_advisory_xact_lock(hashtextextended(?::text,0))",
                                        Boolean.class,
                                        order.toString()))) continue;
                        var jobs =
                                jdbc.query(
                                        """
                    SELECT * FROM fulfillment_jobs WHERE order_id=? AND
                    ((status='READY' AND next_attempt_at<=now()) OR (status='RUNNING' AND lease_until<=now()))
                    ORDER BY generation DESC LIMIT 1 FOR UPDATE
                    """,
                                        this::readJob,
                                        order);
                        if (jobs.isEmpty()) continue;
                        JobLease job = jobs.getFirst();
                        if (job.attempts() >= 3) {
                            fail(
                                    job.command(),
                                    "Carrier attempt lease expired after maximum attempts; retry"
                                            + " fulfillment to reconcile");
                            continue;
                        }
                        UUID token = UUID.randomUUID();
                        jdbc.update(
                                """
                    UPDATE fulfillment_jobs SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=now()
                    WHERE order_id=? AND generation=?
                    """,
                                token,
                                Timestamp.from(Instant.now().plus(leaseDuration)),
                                order,
                                job.command().generation());
                        return Optional.of(new JobLease(job.command(), token, job.attempts() + 1));
                    }
                    return Optional.empty();
                });
    }

    void complete(JobLease lease, CarrierOutcome outcome) {
        transactions.executeWithoutResult(
                tx -> {
                    Command command = lease.command();
                    lock(command.orderId());
                    if (outcome.success()) {
                        // A carrier acceptance remains true even after this lease/generation
                        // expired.
                        jdbc.update(
                                "INSERT INTO"
                                    + " fulfillment_shipments(order_id,shipment_id,tracking_number)"
                                    + " VALUES (?,?,?) ON CONFLICT(order_id) DO NOTHING",
                                command.orderId(),
                                outcome.shipmentId(),
                                outcome.trackingNumber());
                        var stored =
                                jdbc.queryForObject(
                                        "SELECT shipment_id,tracking_number FROM"
                                                + " fulfillment_shipments WHERE order_id=?",
                                        (rs, i) ->
                                                CarrierOutcome.accepted(
                                                        rs.getString(1), rs.getString(2)),
                                        command.orderId());
                        Integer generation =
                                jdbc.queryForObject(
                                        "SELECT max(generation) FROM fulfillment_jobs WHERE"
                                                + " order_id=?",
                                        Integer.class,
                                        command.orderId());
                        success(command.orderId(), generation, stored);
                        return;
                    }
                    var jobs =
                            jdbc.query(
                                    "SELECT * FROM fulfillment_jobs WHERE order_id=? AND"
                                            + " generation=? FOR UPDATE",
                                    this::readJob,
                                    command.orderId(),
                                    command.generation());
                    if (jobs.isEmpty() || !lease.token().equals(jobs.getFirst().token())) return;
                    String status =
                            jdbc.queryForObject(
                                    "SELECT status FROM fulfillment_jobs WHERE order_id=? AND"
                                            + " generation=?",
                                    String.class,
                                    command.orderId(),
                                    command.generation());
                    if (!"RUNNING".equals(status)) return;
                    if (retries.retry(jobs.getFirst().attempts(), outcome.retryable())) {
                        jdbc.update(
                                """
                    UPDATE fulfillment_jobs SET status='READY',next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_error=?,updated_at=now()
                    WHERE order_id=? AND generation=?
                    """,
                                Timestamp.from(
                                        Instant.now()
                                                .plus(retries.delay(jobs.getFirst().attempts()))),
                                outcome.message(),
                                command.orderId(),
                                command.generation());
                    } else fail(command, outcome.message());
                });
    }

    private void success(UUID order, int generation, CarrierOutcome result) {
        jdbc.update(
                "UPDATE fulfillment_jobs SET"
                    + " status='SUCCEEDED',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=now()"
                    + " WHERE order_id=? AND generation=?",
                order,
                generation);
        emit(order, generation, "ShipmentCreated", result);
    }

    private void fail(Command command, String message) {
        jdbc.update(
                "UPDATE fulfillment_jobs SET"
                    + " status='FAILED',lease_token=NULL,lease_until=NULL,last_error=?,updated_at=now()"
                    + " WHERE order_id=? AND generation=?",
                message,
                command.orderId(),
                command.generation());
        emit(
                command.orderId(),
                command.generation(),
                "FulfillmentFailed",
                CarrierOutcome.failed(false, message));
    }

    private void emit(UUID order, int generation, String type, CarrierOutcome result) {
        UUID event = UUID.randomUUID();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", event.toString())
                .put("orderId", order.toString())
                .put("eventType", type)
                .put("schemaVersion", 1)
                .put("occurredAt", Instant.now().toString());
        envelope.putObject("payload")
                .put("attemptGeneration", generation)
                .put("shipmentId", result.shipmentId())
                .put("trackingNumber", result.trackingNumber())
                .put("message", result.message());
        jdbc.update(
                "INSERT INTO fulfillment_outbox(event_id,order_id,generation,event_type,event_json)"
                    + " VALUES (?,?,?,?,?::jsonb) ON CONFLICT(order_id,generation,event_type) DO"
                    + " NOTHING",
                event,
                order,
                generation,
                type,
                envelope.toString());
    }

    interface Sender {
        void send(String key, String json);
    }

    FulfillmentMetrics metrics() {
        return jdbc.queryForObject(
                """
            SELECT count(*) FILTER (WHERE status='READY') AS ready,
                   count(*) FILTER (WHERE status='RUNNING') AS running,
                   count(*) FILTER (WHERE status='FAILED') AS failed,
                   (SELECT count(*) FROM fulfillment_outbox WHERE published_at IS NULL) AS unpublished,
                   (SELECT count(*) FROM fulfillment_shipments) AS shipments
            FROM fulfillment_jobs
            """,
                (rs, row) ->
                        new FulfillmentMetrics(
                                rs.getLong("ready"),
                                rs.getLong("running"),
                                rs.getLong("failed"),
                                rs.getLong("unpublished"),
                                rs.getLong("shipments")));
    }

    void publish(Sender sender) {
        // One result per bounded transaction; retain row until acknowledged by transport.
        transactions.executeWithoutResult(
                tx -> {
                    var rows =
                            jdbc.query(
                                    "SELECT event_id,order_id,event_json::text FROM"
                                        + " fulfillment_outbox WHERE published_at IS NULL ORDER BY"
                                        + " created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                                    (rs, i) ->
                                            new String[] {
                                                rs.getString(1), rs.getString(2), rs.getString(3)
                                            });
                    for (String[] row : rows) {
                        try (var orderContext = MDC.putCloseable("orderId", row[1]);
                                var eventContext = MDC.putCloseable("eventId", row[0])) {
                            sender.send(row[1], row[2]);
                            jdbc.update(
                                    "UPDATE fulfillment_outbox SET published_at=now() WHERE"
                                            + " event_id=?",
                                    UUID.fromString(row[0]));
                            LoggerFactory.getLogger(FulfillmentStore.class)
                                    .info("Result broker acknowledged event");
                        }
                    }
                });
    }

    private void lock(UUID order) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
                Object.class,
                order.toString());
    }

    private JobLease readJob(ResultSet rs, int row) throws SQLException {
        return new JobLease(
                codec.decode(rs.getString("command_json")),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("attempts"));
    }

    private ObjectNode identity(Command command) {
        ObjectNode identity = command.payload().deepCopy();
        identity.remove("attemptGeneration");
        var sorted = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        identity.path("items").forEach(sorted::add);
        sorted.sort(Comparator.comparing(item -> item.path("sku").asText()));
        var items = mapper.createArrayNode();
        sorted.forEach(items::add);
        identity.set("items", items);
        return identity;
    }
}
