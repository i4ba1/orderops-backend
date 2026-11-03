package com.orderops.orders.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

@Testcontainers
class LegacyMigrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradingFoundationQuarantinesAddresslessCommandsAndPreservesOrderHistory() {
        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO customer_orders(id,customer_reference,status,created_at) VALUES"
                        + " (?,'LEGACY','CONFIRMED',CURRENT_TIMESTAMP)",
                id);
        jdbc.update(
                "INSERT INTO"
                    + " outbox_events(id,aggregate_id,event_type,schema_version,occurred_at,payload)"
                    + " VALUES"
                    + " (?,?,'OrderConfirmed',1,CURRENT_TIMESTAMP,'{\"payload\":{\"attemptGeneration\":1}}'::jsonb)",
                UUID.randomUUID(),
                id);
        jdbc.update(
                "INSERT INTO"
                    + " outbox_events(id,aggregate_id,event_type,schema_version,occurred_at,payload)"
                    + " VALUES"
                    + " (?,?,'OrderConfirmed',1,CURRENT_TIMESTAMP,'{\"payload\":{\"shippingAddress\":null}}'::jsonb)",
                UUID.randomUUID(),
                id);
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events WHERE quarantine_reason LIKE"
                                        + " 'Legacy%' AND published_at IS NULL",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM order_timeline WHERE order_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND"
                                        + " quarantine_reason IS NULL",
                                Integer.class))
                .isZero();
    }
}
