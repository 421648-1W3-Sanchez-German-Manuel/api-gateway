package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-24 - the gateway limits by IP (a flood guard); auth/ limits by e-mail (a
 * brute-force guard). Different keys: they cannot fire on the same condition,
 * and whichever trips first answers.
 *
 * The gateway is the ONLY one that can cut before the round trip to the
 * database and the BCrypt, which is expensive on purpose (~100 ms).
 */
@TestPropertySource(properties = {
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.trusted-proxies[0]=127.0.0.1/32",
        "gateway.rate-limit.expensive-routes[0].path=/api/users/public/auth/**",
        "gateway.rate-limit.expensive-routes[0].key=IP",
        "gateway.rate-limit.expensive-routes[0].capacity=3",
        "gateway.rate-limit.expensive-routes[0].refill-per-minute=3"
})
class RateLimitForwardedIT extends AbstractGatewayTest {

    @Test
    void dos_IPs_distintas_detras_del_MISMO_proxy_consumen_buckets_SEPARADOS() {
        // The gotcha that never shows up in development: a naive implementation
        // takes the load balancer's IP and rate-limits THE WHOLE INTERNET as if
        // it were one client. The limiter is useless and nobody finds out until
        // the exam-week peak.
        for (int i = 0; i < 3; i++) {
            cliente.post().uri("/api/users/public/auth/login")
                    .header("X-Forwarded-For", "203.0.113.10")
                    .exchange().expectStatus().isOk();
        }
        cliente.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.10")
                .exchange().expectStatus().isEqualTo(429);

        // The OTHER IP still has its full budget.
        cliente.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.99")
                .exchange().expectStatus().isOk();
    }

    @Test
    void el_429_lleva_Retry_After_y_el_type_compartido() {
        for (int i = 0; i < 4; i++) {
            cliente.post().uri("/api/users/public/auth/login")
                    .header("X-Forwarded-For", "203.0.113.20").exchange();
        }
        cliente.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.20")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.type").value(t ->
                        assertThat((String) t).endsWith("/too-many-attempts"));
    }

    @Test
    void una_ruta_que_NO_esta_en_expensive_routes_no_se_limita() {
        // The filter is a no-op off the list: we do not want to limit everything.
        for (int i = 0; i < 20; i++) {
            cliente.get().uri("/api/users/public/legal/terms")
                    .header("X-Forwarded-For", "203.0.113.30")
                    .exchange().expectStatus().isOk();
        }
    }
}
