package com.orderops.orders.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "health", description = "Process liveness")
public class HealthController {
    @Operation(summary = "Application health", description = "The process is serving requests; this is not a dependency readiness check.")
    @ApiResponse(responseCode = "200", description = "Process up", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"UP\",\"service\":\"order-service\"}")))
    @GetMapping("/internal/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "order-service");
    }
}
