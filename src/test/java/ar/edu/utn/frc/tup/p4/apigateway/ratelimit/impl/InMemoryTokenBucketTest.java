package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenBucketTest {

    private final InMemoryTokenBucket bucket = new InMemoryTokenBucket();

    @Test
    void consume_drains_the_bucket_and_then_rejects() {
        assertThat(bucket.consume("k1", 2, 120)).isTrue();
        assertThat(bucket.consume("k1", 2, 120)).isTrue();
        assertThat(bucket.consume("k1", 2, 120)).isFalse();
    }

    @Test
    void suggestedWait_reflects_the_real_refill_not_a_fixed_one() {
        // capacity 2, refill 60/min = 1 token per second. Drained -> ~1 s.
        bucket.consume("k2", 2, 60);
        bucket.consume("k2", 2, 60);
        assertThat(bucket.consume("k2", 2, 60)).isFalse();

        Duration wait = bucket.suggestedWait("k2");
        assertThat(wait.getSeconds()).isBetween(1L, 2L);
    }

    @Test
    void suggestedWait_scales_with_the_slow_refill() {
        // capacity 10, refill 10/min = 1 token every 6 s. Drained -> ~6 s,
        // not the fixed 60 s from before.
        for (int i = 0; i < 10; i++) {
            assertThat(bucket.consume("k3", 10, 10)).isTrue();
        }
        assertThat(bucket.consume("k3", 10, 10)).isFalse();

        Duration wait = bucket.suggestedWait("k3");
        assertThat(wait.getSeconds()).isBetween(1L, 6L);
    }

    @Test
    void suggestedWait_for_an_unknown_key_is_zero() {
        assertThat(bucket.suggestedWait("nobody")).isEqualTo(Duration.ZERO);
    }
}
