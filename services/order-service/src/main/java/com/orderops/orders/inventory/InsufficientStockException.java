package com.orderops.orders.inventory;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String sku) {
        super("Insufficient stock for SKU: " + sku);
    }
}
