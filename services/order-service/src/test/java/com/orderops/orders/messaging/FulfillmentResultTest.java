package com.orderops.orders.messaging;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FulfillmentResultTest {
    private final ObjectMapper mapper = new ObjectMapper();

    static String event(String type, String payload) {
        return """
                {"eventId":"ad3c979a-de11-40c9-9ec6-e90eef48447b","orderId":"e57ccf95-4357-4ccb-b30a-7fa15748f717","eventType":"%s","schemaVersion":1,"occurredAt":"2026-11-02T00:00:00Z","payload":%s}
                """
                .formatted(type, payload);
    }

    @Test
    void aValidShipmentResultPreservesIdentifiersAndGeneration() {
        var result =
                FulfillmentResult.parse(
                        mapper,
                        event(
                                "ShipmentCreated",
                                "{\"attemptGeneration\":2,\"shipmentId\":\"SHP-1\",\"trackingNumber\":\"TRACK-1\",\"message\":null}"));
        assertThat(result.generation()).isEqualTo(2);
        assertThat(result.shipmentId()).isEqualTo("SHP-1");
        assertThat(result.orderId().toString()).isEqualTo("e57ccf95-4357-4ccb-b30a-7fa15748f717");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"attemptGeneration\":1.5,\"message\":\"Unavailable\"}",
                "{\"attemptGeneration\":\"1\",\"message\":\"Unavailable\"}",
                "{\"attemptGeneration\":0,\"message\":\"Unavailable\"}",
                "{\"attemptGeneration\":1,\"message\":\" \"}"
            })
    void malformedFailurePayloadsArePoisonRatherThanDatabaseEffects(String payload) {
        assertThatThrownBy(
                        () -> FulfillmentResult.parse(mapper, event("FulfillmentFailed", payload)))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void unknownVersionsMissingIdsAndMissingShipmentIdentifiersAreRejected() {
        String valid =
                event("FulfillmentFailed", "{\"attemptGeneration\":1,\"message\":\"Unavailable\"}");
        assertThatThrownBy(
                        () ->
                                FulfillmentResult.parse(
                                        mapper,
                                        valid.replace(
                                                "\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(
                        () ->
                                FulfillmentResult.parse(
                                        mapper,
                                        valid.replace(
                                                "\"schemaVersion\":1",
                                                "\"schemaVersion\":4294967297")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(
                        () ->
                                FulfillmentResult.parse(
                                        mapper,
                                        valid.replace("ad3c979a-de11-40c9-9ec6-e90eef48447b", "")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(
                        () ->
                                FulfillmentResult.parse(
                                        mapper,
                                        event("ShipmentCreated", "{\"attemptGeneration\":1}")))
                .isInstanceOf(InvalidEventException.class);
    }
}
