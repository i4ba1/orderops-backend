package com.orderops.fulfillment;

record FulfillmentMetrics(
        long readyJobs,
        long runningJobs,
        long failedJobs,
        long unpublishedOutbox,
        long shipments) {}
