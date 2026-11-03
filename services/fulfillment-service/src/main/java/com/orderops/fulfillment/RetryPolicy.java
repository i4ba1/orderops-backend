package com.orderops.fulfillment;

import java.time.Duration;

record RetryPolicy(Duration backoff) {
    boolean retry(int attempts, boolean retryable) {
        return retryable && attempts < 3;
    }

    Duration delay(int attempts) {
        return backoff.multipliedBy(1L << Math.min(10, Math.max(0, attempts - 1)));
    }
}
