package com.orderops.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

record Command(UUID eventId, UUID orderId, int generation, JsonNode payload, String wire) {}
