package com.orderops.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class CommandCodecTest {
    static final String ORDER = "5a73ec22-e8dc-4cf4-9b54-ffeed157e908";

    static String command(int generation) {
        return """
            {"eventId":"%s","orderId":"%s","eventType":"%s","schemaVersion":1,
             "occurredAt":"2026-11-02T00:00:00Z","payload":{"attemptGeneration":%d,
             "customerReference":"DEMO-1","shippingAddress":{"recipient":"Ada","line1":"10 Street",
             "city":"London","postalCode":"SW1A","country":"GB"},"demoScenario":"NORMAL",
             "items":[{"sku":"COFFEE","quantity":2}]}}
            """
                .formatted(
                        UUID.randomUUID(),
                        ORDER,
                        generation == 1 ? "OrderConfirmed" : "RetryRequested",
                        generation);
    }

    @Test
    void acceptsCompleteVersionedCommand() {
        var command = new CommandCodec(new ObjectMapper()).decode(command(1));
        assertThat(command.orderId().toString()).isEqualTo(ORDER);
        assertThat(command.generation()).isEqualTo(1);
    }

    @Test
    void acceptsCustomerReferencesAtTheOrderApiLimit() {
        assertThatCode(
                        () ->
                                new CommandCodec(new ObjectMapper())
                                        .decode(command(1).replace("DEMO-1", "R".repeat(120))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLegacyAndMalformedPayloadsBeforeAnyDatabaseWork() {
        var codec = new CommandCodec(new ObjectMapper());
        for (String invalid :
                new String[] {
                    "{}",
                    command(1).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                    command(1).replace("\"quantity\":2", "\"quantity\":2.5"),
                    command(1).replace("\"country\":\"GB\"", "\"country\":\"\""),
                    command(1).replace("\"attemptGeneration\":1", "\"attemptGeneration\":0"),
                    command(1).replace("\"quantity\":2", "\"quantity\":1001"),
                    command(1).replace("COFFEE", "invalid sku")
                }) {
            assertThatThrownBy(() -> codec.decode(invalid))
                    .isInstanceOf(InvalidCommandException.class);
        }
    }
}
