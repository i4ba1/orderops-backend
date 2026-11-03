package com.orderops.fulfillment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

class WorkerLoggingTest {
    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void scopesDurableIdentifiersAndLogsOutcomeWithoutCarrierDetails() {
        var command = new CommandCodec(new ObjectMapper()).decode(CommandCodecTest.command(1));
        var job = new JobLease(command, UUID.randomUUID(), 1);
        var store = mock(FulfillmentStore.class);
        var carrier = mock(CarrierClient.class);
        when(store.claim()).thenReturn(Optional.of(job));
        when(carrier.create(command))
                .thenAnswer(
                        invocation -> {
                            assertThat(MDC.get("orderId")).isEqualTo(command.orderId().toString());
                            assertThat(MDC.get("eventId")).isEqualTo(command.eventId().toString());
                            return CarrierOutcome.failed(true, "secret-carrier-body");
                        });
        var logger = (Logger) LoggerFactory.getLogger(FulfillmentWorker.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new FulfillmentWorker(store, carrier).tick();
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list)
                    .allSatisfy(
                            event -> {
                                assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                                        .doesNotContain(
                                                "secret-carrier-body", "Ada", "shippingAddress");
                                assertThat(event.getThrowableProxy()).isNull();
                            });
            assertThat(MDC.get("orderId")).isNull();
            assertThat(MDC.get("eventId")).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void cleansDurableContextWhenCarrierFailsUnexpectedly() {
        var command = new CommandCodec(new ObjectMapper()).decode(CommandCodecTest.command(1));
        var store = mock(FulfillmentStore.class);
        var carrier = mock(CarrierClient.class);
        when(store.claim()).thenReturn(Optional.of(new JobLease(command, UUID.randomUUID(), 1)));
        when(carrier.create(command)).thenThrow(new IllegalStateException("private details"));
        assertThatThrownBy(() -> new FulfillmentWorker(store, carrier).tick())
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("orderId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }
}
