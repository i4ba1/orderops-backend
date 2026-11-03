package com.orderops.orders.messaging;

import com.fasterxml.jackson.databind.*;

import java.time.Instant;
import java.util.UUID;

public record FulfillmentResult(
        UUID eventId,
        UUID orderId,
        String type,
        Instant occurredAt,
        int generation,
        String shipmentId,
        String trackingNumber,
        String message) {
    public static FulfillmentResult parse(ObjectMapper mapper, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null
                    || !root.isObject()
                    || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("schemaVersion").canConvertToInt()
                    || root.path("schemaVersion").intValue() != 1)
                throw new InvalidEventException("Unsupported or missing event schema version");
            var eventId = uuid(text(root, "eventId", 36));
            var orderId = uuid(text(root, "orderId", 36));
            var type = text(root, "eventType", 64);
            if (!type.equals("ShipmentCreated") && !type.equals("FulfillmentFailed"))
                throw new InvalidEventException("Unsupported fulfillment result type");
            var occurredAt = Instant.parse(text(root, "occurredAt", 64));
            var payload = root.path("payload");
            if (!payload.isObject()
                    || !payload.path("attemptGeneration").isIntegralNumber()
                    || !payload.path("attemptGeneration").canConvertToInt()
                    || payload.path("attemptGeneration").intValue() < 1)
                throw new InvalidEventException("Invalid attempt generation");
            var generation = payload.path("attemptGeneration").intValue();
            String shipment = null, tracking = null, message = null;
            if (type.equals("ShipmentCreated")) {
                shipment = text(payload, "shipmentId", 160);
                tracking = text(payload, "trackingNumber", 160);
            } else {
                message = text(payload, "message", 500);
                optionalText(payload, "shipmentId", 160);
                optionalText(payload, "trackingNumber", 160);
            }
            return new FulfillmentResult(
                    eventId, orderId, type, occurredAt, generation, shipment, tracking, message);
        } catch (InvalidEventException invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw new InvalidEventException("Malformed fulfillment event");
        }
    }

    private static UUID uuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text))
            throw new InvalidEventException("Invalid UUID identifier");
        return id;
    }

    private static String text(JsonNode parent, String name, int max) {
        var node = parent.path(name);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max)
            throw new InvalidEventException("Missing or invalid " + name);
        return node.textValue();
    }

    private static void optionalText(JsonNode parent, String name, int max) {
        var value = parent.get(name);
        if (value != null && !value.isNull()) text(parent, name, max);
    }
}
