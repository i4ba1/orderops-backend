package com.orderops.orders.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class IdempotencyStore {
    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8 to 100 ASCII letters, digits, underscores or"
                            + " hyphens");
    }

    public Optional<Claim> lockAndFind(String actor, String operation, String key) {
        validateKey(key);
        // Transaction-scoped database lock also serializes keys that have no persisted claim yet.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> {},
                actor.length() + ":" + actor + operation.length() + ":" + operation + key);
        return jdbc
                .query(
                        "SELECT request_hash, order_id FROM idempotency_claims WHERE actor=? AND"
                                + " operation=? AND request_key=?",
                        (rs, n) -> new Claim(rs.getString(1), rs.getObject(2, UUID.class)),
                        actor,
                        operation,
                        key)
                .stream()
                .findFirst();
    }

    public void claim(String actor, String operation, String key, String hash, UUID orderId) {
        jdbc.update(
                "INSERT INTO idempotency_claims(actor,operation,request_key,request_hash,order_id)"
                        + " VALUES (?,?,?,?,?)",
                actor,
                operation,
                key,
                hash,
                orderId);
    }

    public record Claim(String hash, UUID orderId) {}
}
