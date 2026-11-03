package com.orderops.orders.inventory;

public record InventoryResponse(String sku, String name, int availableQuantity) {
    public static InventoryResponse from(InventoryItem item) {
        return new InventoryResponse(item.getSku(), item.getName(), item.getAvailableQuantity());
    }
}
