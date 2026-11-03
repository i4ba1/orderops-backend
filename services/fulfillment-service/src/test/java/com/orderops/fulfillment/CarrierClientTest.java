package com.orderops.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

class CarrierClientTest {
    @Test
    void rejectsIdentifiersThatCannotFitTheResultEventContract() throws Exception {
        for (String field : new String[] {"shipmentId", "trackingNumber"}) {
            var mapper = new ObjectMapper();
            var result =
                    mapper.createObjectNode()
                            .put("shipmentId", "shipment-1")
                            .put("trackingNumber", "TRACK-1")
                            .put("createdAt", "2026-11-02T00:00:00Z");
            result.put(field, "x".repeat(161));
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(
                    "/shipments",
                    exchange -> {
                        var response = mapper.writeValueAsBytes(result);
                        exchange.sendResponseHeaders(201, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                    });
            server.start();
            try {
                var outcome =
                        new CarrierClient(
                                        mapper,
                                        "http://localhost:" + server.getAddress().getPort(),
                                        Duration.ofSeconds(1))
                                .create(
                                        new CommandCodec(mapper)
                                                .decode(CommandCodecTest.command(1)));
                assertThat(outcome.success()).isFalse();
                assertThat(outcome.retryable()).isTrue();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void sendsStableOrderKeyAndCompleteAddressAndAcceptsCreatedResponse() throws Exception {
        var key = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/shipments",
                exchange -> {
                    key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    byte[] response =
                            "{\"shipmentId\":\"carrier-1\",\"trackingNumber\":\"TRACK-1\",\"createdAt\":\"2026-11-02T00:00:00Z\"}"
                                    .getBytes();
                    exchange.sendResponseHeaders(201, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var client =
                    new CarrierClient(
                            mapper,
                            "http://localhost:" + server.getAddress().getPort(),
                            Duration.ofSeconds(1));
            var outcome =
                    client.create(new CommandCodec(mapper).decode(CommandCodecTest.command(1)));
            assertThat(outcome.success()).isTrue();
            assertThat(outcome.shipmentId()).isEqualTo("carrier-1");
            assertThat(key.get()).isEqualTo(CommandCodecTest.ORDER);
            assertThat(mapper.readTree(body.get()).at("/shippingAddress/recipient").asText())
                    .isEqualTo("Ada");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesRateLimitsAndUnavailableAsRetryableButBadRequestsAsTerminal() throws Exception {
        for (int status : new int[] {429, 503, 400}) {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(
                    "/shipments",
                    e -> {
                        e.sendResponseHeaders(status, -1);
                        e.close();
                    });
            server.start();
            try {
                var mapper = new ObjectMapper();
                var outcome =
                        new CarrierClient(
                                        mapper,
                                        "http://localhost:" + server.getAddress().getPort(),
                                        Duration.ofSeconds(1))
                                .create(
                                        new CommandCodec(mapper)
                                                .decode(CommandCodecTest.command(1)));
                assertThat(outcome.success()).isFalse();
                assertThat(outcome.retryable()).isEqualTo(status != 400);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void timeoutAfterCreationRetriesTheSameKeyAndReturnsOneStoredShipment() throws Exception {
        var keys = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(
                "/shipments",
                e -> {
                    keys.add(e.getRequestHeaders().getFirst("Idempotency-Key"));
                    if (requests.incrementAndGet() == 1) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] response =
                            "{\"shipmentId\":\"stored-once\",\"trackingNumber\":\"TRACK-ONCE\",\"createdAt\":\"2026-11-02T00:00:00Z\"}"
                                    .getBytes();
                    try {
                        e.sendResponseHeaders(200, response.length);
                        e.getResponseBody().write(response);
                    } finally {
                        e.close();
                    }
                });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var client =
                    new CarrierClient(
                            mapper,
                            "http://localhost:" + server.getAddress().getPort(),
                            Duration.ofMillis(150));
            var command = new CommandCodec(mapper).decode(CommandCodecTest.command(1));
            var result = client.create(command);
            assertThat(result.retryable()).isTrue();
            assertThat(result.success()).isFalse();
            var recovered = client.create(command);
            assertThat(recovered.success()).isTrue();
            assertThat(recovered.shipmentId()).isEqualTo("stored-once");
            assertThat(keys).containsExactly(CommandCodecTest.ORDER, CommandCodecTest.ORDER);
        } finally {
            server.stop(0);
            executor.close();
        }
    }
}
