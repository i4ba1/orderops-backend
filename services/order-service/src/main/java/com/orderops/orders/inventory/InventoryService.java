package com.orderops.orders.inventory;

import com.orderops.orders.order.Order;
import com.orderops.orders.order.OrderItem;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class InventoryService {
    private final InventoryRepository inventory;

    public InventoryService(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> list() {
        return inventory.findAll(Sort.by("sku")).stream().map(InventoryResponse::from).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockReservation> reserve(Order order) {
        var pending = new ArrayList<StockReservation>();
        for (var line :
                order.getItems().stream()
                        .sorted(Comparator.comparing(OrderItem::getSku))
                        .toList()) {
            var stock =
                    inventory
                            .findForUpdate(line.getSku())
                            .orElseThrow(
                                    () ->
                                            new EntityNotFoundException(
                                                    "Inventory item not found: " + line.getSku()));
            stock.reserve(line.getQuantity());
            pending.add(new StockReservation(order.getId(), line.getSku(), line.getQuantity()));
        }
        return pending;
    }
}
