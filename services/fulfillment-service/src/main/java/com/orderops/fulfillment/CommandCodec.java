package com.orderops.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Component
class CommandCodec {
    private final ObjectMapper mapper;

    CommandCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Command decode(String wire) {
        try {
            JsonNode root = mapper.readTree(wire);
            require(root.isObject());
            UUID event = UUID.fromString(text(root, "eventId", 36));
            UUID order = UUID.fromString(text(root, "orderId", 36));
            require(
                    root.path("schemaVersion").isInt()
                            && root.path("schemaVersion").intValue() == 1);
            String type = text(root, "eventType", 40);
            require(Set.of("OrderConfirmed", "RetryRequested").contains(type));
            Instant.parse(text(root, "occurredAt", 60));
            JsonNode p = root.path("payload");
            require(
                    p.isObject()
                            && p.path("attemptGeneration").isIntegralNumber()
                            && p.path("attemptGeneration").canConvertToInt());
            int generation = p.path("attemptGeneration").intValue();
            require(type.equals("RetryRequested") ? generation > 1 : generation == 1);
            text(p, "customerReference", 120);
            JsonNode address = p.path("shippingAddress");
            text(address, "recipient", 120);
            text(address, "line1", 200);
            text(address, "city", 100);
            text(address, "postalCode", 32);
            require(text(address, "country", 2).matches("[A-Z]{2}"));
            require(
                    Set.of("NORMAL", "FAIL_THEN_RECOVER", "TIMEOUT_AFTER_CREATE")
                            .contains(text(p, "demoScenario", 40)));
            JsonNode items = p.path("items");
            require(items.isArray() && !items.isEmpty() && items.size() <= 20);
            var seen = new java.util.HashSet<String>();
            for (JsonNode item : items) {
                String sku = text(item, "sku", 64);
                require(sku.matches("[A-Z0-9][A-Z0-9_-]{0,63}") && seen.add(sku));
                JsonNode quantity = item.path("quantity");
                require(
                        quantity.isIntegralNumber()
                                && quantity.canConvertToInt()
                                && quantity.intValue() > 0
                                && quantity.intValue() <= 1000);
            }
            return new Command(event, order, generation, p, wire);
        } catch (Exception failure) {
            throw new InvalidCommandException();
        }
    }

    private static String text(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        require(
                value.isTextual()
                        && !value.textValue().isBlank()
                        && value.textValue().length() <= max);
        return value.textValue();
    }

    private static void require(boolean valid) {
        if (!valid) throw new InvalidCommandException();
    }
}
