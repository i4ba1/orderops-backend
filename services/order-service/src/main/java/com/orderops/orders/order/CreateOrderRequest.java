package com.orderops.orders.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record CreateOrderRequest(
        @NotBlank @Size(max = 120) String customerReference,
        @NotNull @Valid ShippingAddress shippingAddress,
        DemoScenario demoScenario,
        @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items) {
    public CreateOrderRequest {
        if (demoScenario == null) demoScenario = DemoScenario.NORMAL;
    }

    public record Item(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String sku,
            @Min(1) @Max(1000) int quantity) {}
}
