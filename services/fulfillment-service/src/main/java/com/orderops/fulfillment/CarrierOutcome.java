package com.orderops.fulfillment;

record CarrierOutcome(
        boolean success,
        boolean retryable,
        String shipmentId,
        String trackingNumber,
        String message) {
    static CarrierOutcome accepted(String shipmentId, String trackingNumber) {
        return new CarrierOutcome(
                true, false, shipmentId, trackingNumber, "Carrier accepted shipment request");
    }

    static CarrierOutcome failed(boolean retryable, String message) {
        return new CarrierOutcome(false, retryable, null, null, message);
    }
}
