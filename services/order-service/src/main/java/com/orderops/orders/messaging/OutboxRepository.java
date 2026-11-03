package com.orderops.orders.messaging;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query(
            value =
                    "SELECT * FROM outbox_events WHERE published_at IS NULL AND quarantine_reason"
                            + " IS NULL ORDER BY occurred_at,id LIMIT 5 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch();
}
