package com.orderops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class RetryPolicyTest {
    @Test
    void stopsAfterThreePersistedAttempts() {
        var policy = new RetryPolicy(Duration.ofSeconds(2));
        assertThat(policy.retry(1, true)).isTrue();
        assertThat(policy.retry(2, true)).isTrue();
        assertThat(policy.retry(3, true)).isFalse();
        assertThat(policy.retry(1, false)).isFalse();
        assertThat(policy.delay(2)).isEqualTo(Duration.ofSeconds(4));
    }
}
