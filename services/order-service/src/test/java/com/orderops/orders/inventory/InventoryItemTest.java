package com.orderops.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InventoryItemTest {
    @Test
    void reservingTheLastUnitsMakesThemUnavailable() {
        var item = new InventoryItem("COFFEE", "Coffee", 3);
        item.reserve(3);
        assertThat(item.getAvailableQuantity()).isZero();
    }

    @Test
    void insufficientStockLeavesAvailabilityUnchanged() {
        var item = new InventoryItem("COFFEE", "Coffee", 2);
        assertThatThrownBy(() -> item.reserve(3)).isInstanceOf(InsufficientStockException.class);
        assertThat(item.getAvailableQuantity()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void invalidReservationsCannotIncreaseOrConsumeStock(int quantity) {
        var item = new InventoryItem("COFFEE", "Coffee", 5);
        assertThatThrownBy(() -> item.reserve(quantity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(item.getAvailableQuantity()).isEqualTo(5);
    }
}
