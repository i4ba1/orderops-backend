package com.orderops.orders.inventory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@Tag(name = "inventory", description = "Available stock for new reservations")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(summary = "List inventory", description = "Products sorted by SKU with their available quantity.")
    @ApiResponse(responseCode = "200", description = "Inventory list", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
            [
              { "sku": "COFFEE", "name": "Coffee beans", "availableQuantity": 10 },
              { "sku": "MUG", "name": "Ceramic mug", "availableQuantity": 1 },
              { "sku": "TEA", "name": "Green tea", "availableQuantity": 5 }
            ]
            """)))
    @GetMapping
    public List<InventoryResponse> list() {
        return inventory.list();
    }
}
