package com.orderops.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
class CarrierClient {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    CarrierClient(
            ObjectMapper mapper,
            @Value("${orderops.carrier.base-url:http://localhost:8090}") String baseUrl,
            @Value("${orderops.carrier.timeout:PT1S}") Duration timeout) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl + "/shipments");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    CarrierOutcome create(Command command) {
        try {
            String body =
                    mapper.writeValueAsString(
                            Map.of(
                                    "orderId",
                                    command.orderId().toString(),
                                    "shippingAddress",
                                    command.payload().get("shippingAddress"),
                                    "demoScenario",
                                    command.payload().get("demoScenario")));
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", command.orderId().toString())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200 || status == 201) {
                var result = mapper.readTree(response.body());
                String id = result.path("shipmentId").asText("");
                String tracking = result.path("trackingNumber").asText("");
                Instant.parse(result.path("createdAt").asText());
                if (id.isBlank()
                        || tracking.isBlank()
                        || id.length() > 160
                        || tracking.length() > 160)
                    return CarrierOutcome.failed(
                            true, "Carrier returned an invalid shipment response");
                return CarrierOutcome.accepted(id, tracking);
            }
            return CarrierOutcome.failed(
                    status == 429 || status >= 500, "Carrier returned HTTP " + status);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CarrierOutcome.failed(true, "Carrier request interrupted");
        } catch (Exception failure) {
            return CarrierOutcome.failed(
                    true, "Carrier request timed out, unavailable or returned an invalid response");
        }
    }
}
