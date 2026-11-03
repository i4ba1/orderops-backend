package com.orderops.orders.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderItem {
    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {}

    public OrderItem(String sku, int quantity) {
        if (sku == null || !sku.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(
                    "SKU must contain uppercase letters, digits, underscores or hyphens");
        }
        if (quantity < 1 || quantity > 1000) {
            throw new IllegalArgumentException("Quantity must be between 1 and 1000");
        }
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }
}
