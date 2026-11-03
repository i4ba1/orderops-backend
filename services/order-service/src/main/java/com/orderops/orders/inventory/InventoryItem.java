package com.orderops.orders.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {
    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private int availableQuantity;

    protected InventoryItem() {}

    public InventoryItem(String sku, String name, int availableQuantity) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("Available quantity cannot be negative");
        }
        this.sku = sku;
        this.name = name;
        this.availableQuantity = availableQuantity;
    }

    public void reserve(int quantity) {
        if (quantity < 1 || quantity > 1000) {
            throw new IllegalArgumentException("Quantity must be between 1 and 1000");
        }
        if (availableQuantity < quantity) {
            throw new InsufficientStockException(sku);
        }
        availableQuantity -= quantity;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
