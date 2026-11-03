package com.orderops.orders.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.orders.order.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

class OutboxLoggingTest {
    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void scopesStableIdentifiersAroundPublishAndCleansThemAfterFailure() {
        var order =
                Order.confirm(
                        "private-customer",
                        new ShippingAddress(
                                "Private Name", "Private Street", "London", "ABC", "GB"),
                        DemoScenario.NORMAL,
                        List.of(new OrderItem("COFFEE", 1)));
        var event = OutboxEvent.orderConfirmed(order);
        var repository = mock(OutboxRepository.class);
        when(repository.lockUnpublishedBatch()).thenReturn(List.of(event));
        var dispatcher = new OutboxDispatcher(repository, new ObjectMapper());
        assertThatThrownBy(
                        () ->
                                dispatcher.publishBatch(
                                        (key, json) -> {
                                            assertThat(MDC.get("orderId"))
                                                    .isEqualTo(order.getId().toString());
                                            assertThat(MDC.get("eventId"))
                                                    .isEqualTo(event.getId().toString());
                                            throw new IllegalStateException("broker unavailable");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("orderId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }
}
