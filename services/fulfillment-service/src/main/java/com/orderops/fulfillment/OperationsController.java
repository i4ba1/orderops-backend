package com.orderops.fulfillment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperationsController {
    private final FulfillmentStore store;

    OperationsController(FulfillmentStore store) {
        this.store = store;
    }

    @GetMapping("/internal/metrics")
    FulfillmentMetrics metrics() {
        return store.metrics();
    }
}
