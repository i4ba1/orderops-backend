package com.orderops.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

class OrderTest {
    @Test
    void confirmationPreservesTheRequestedItemsAndReference() {
        var order =
                Order.confirm(
                        "CUSTOMER-42",
                        OrderLifecycleTest.address(),
                        DemoScenario.NORMAL,
                        List.of(new OrderItem("COFFEE", 2)));
        assertThat(order.getId()).isNotNull();
        assertThat(order.getCustomerReference()).isEqualTo("CUSTOMER-42");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getItems())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.getSku()).isEqualTo("COFFEE");
                            assertThat(item.getQuantity()).isEqualTo(2);
                        });
    }

    @Test
    void duplicateSkusAreRejectedRatherThanReservedTwice() {
        assertThatThrownBy(
                        () ->
                                Order.confirm(
                                        "CUSTOMER-42",
                                        OrderLifecycleTest.address(),
                                        DemoScenario.NORMAL,
                                        List.of(
                                                new OrderItem("COFFEE", 2),
                                                new OrderItem("COFFEE", 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate SKU");
    }

    @Test
    void anEmptyOrderCannotBeConfirmed() {
        assertThatThrownBy(
                        () ->
                                Order.confirm(
                                        "CUSTOMER-42",
                                        OrderLifecycleTest.address(),
                                        DemoScenario.NORMAL,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void invalidItemQuantitiesAreRejected(int quantity) {
        assertThatThrownBy(() -> new OrderItem("COFFEE", quantity))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
