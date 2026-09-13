package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenBucketTest {

    private final InMemoryTokenBucket bucket = new InMemoryTokenBucket();

    @Test
    void consume_agota_el_bucket_y_luego_rechaza() {
        assertThat(bucket.consume("k1", 2, 120)).isTrue();
        assertThat(bucket.consume("k1", 2, 120)).isTrue();
        assertThat(bucket.consume("k1", 2, 120)).isFalse();
    }

    @Test
    void suggestedWait_refleja_el_refill_real_no_un_fijo() {
        // capacity 2, refill 60/min = 1 token por segundo. Agotado -> ~1 s.
        bucket.consume("k2", 2, 60);
        bucket.consume("k2", 2, 60);
        assertThat(bucket.consume("k2", 2, 60)).isFalse();

        Duration espera = bucket.suggestedWait("k2");
        assertThat(espera.getSeconds()).isBetween(1L, 2L);
    }

    @Test
    void suggestedWait_escala_con_el_refill_lento() {
        // capacity 10, refill 10/min = 1 token cada 6 s. Agotado -> ~6 s,
        // no los 60 s fijos de antes.
        for (int i = 0; i < 10; i++) {
            assertThat(bucket.consume("k3", 10, 10)).isTrue();
        }
        assertThat(bucket.consume("k3", 10, 10)).isFalse();

        Duration espera = bucket.suggestedWait("k3");
        assertThat(espera.getSeconds()).isBetween(1L, 6L);
    }

    @Test
    void suggestedWait_de_key_desconocida_es_cero() {
        assertThat(bucket.suggestedWait("nadie")).isEqualTo(Duration.ZERO);
    }
}
