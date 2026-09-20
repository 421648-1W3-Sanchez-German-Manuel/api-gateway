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
    void two_DIFFERENT_IPs_behind_the_SAME_proxy_consume_SEPARATE_buckets() {
        // The gotcha that never shows up in development: a naive implementation
        // takes the load balancer's IP and rate-limits THE WHOLE INTERNET as if
        // it were one client. The limiter is useless and nobody finds out until
        // the exam-week peak.
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/users/public/auth/login")
                    .header("X-Forwarded-For", "203.0.113.10")
                    .exchange().expectStatus().isOk();
        }
        client.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.10")
                .exchange().expectStatus().isEqualTo(429);

        // The OTHER IP still has its full budget.
        client.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.99")
                .exchange().expectStatus().isOk();
    }

    @Test
    void the_429_carries_Retry_After_and_the_shared_type() {
        for (int i = 0; i < 4; i++) {
            client.post().uri("/api/users/public/auth/login")
                    .header("X-Forwarded-For", "203.0.113.20").exchange();
        }
        client.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.20")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.type").value(t ->
                        assertThat((String) t).endsWith("/too-many-attempts"));
    }

    /**
     * THE hostile case, and the one the two tests above do not cover: they send
     * an X-Forwarded-For the way a well-behaved proxy would, which is the path
     * that already worked.
     *
     * What nginx really puts on the wire is BOTH headers, and they are not
     * equally trustworthy:
     *
     *   X-Real-IP        $remote_addr              -> SET: overwrites whatever
     *                                                the client sent.
     *   X-Forwarded-For  $proxy_add_x_forwarded_for -> APPENDS: the client's
     *                                                value survives, FIRST.
     *
     * So reading X-Forwarded-For[0] reads a value the client chose. A different
     * invented value per request bought a fresh bucket every time and the limit
     * stopped existing -- on the one route whose only protection it is
     * (/registration/**, which sends mail unauthenticated).
     */
    @Test
    void an_INVENTED_X_Forwarded_For_does_NOT_buy_a_new_bucket() {
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/users/public/auth/login")
                    .header("X-Real-IP", "203.0.113.40")
                    .header("X-Forwarded-For", "198.51.100." + i + ", 203.0.113.40")
                    .exchange().expectStatus().isOk();
        }
        // A fourth invented IP: it is the SAME client and the budget is spent.
        client.post().uri("/api/users/public/auth/login")
                .header("X-Real-IP", "203.0.113.40")
                .header("X-Forwarded-For", "198.51.100.99, 203.0.113.40")
                .exchange().expectStatus().isEqualTo(429);
    }

    /**
     * Without X-Real-IP the chain is still read from the RIGHT, skipping hops
     * that are trusted proxies: the rightmost non-trusted entry is the one the
     * nearest proxy actually observed, and it is the only one no client can
     * choose. Everything to its left is hearsay.
     */
    @Test
    void without_X_Real_IP_the_client_is_taken_from_the_RIGHT_of_the_chain() {
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/users/public/auth/login")
                    .header("X-Forwarded-For", "198.51.100." + i + ", 203.0.113.60")
                    .exchange().expectStatus().isOk();
        }
        client.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "198.51.100.99, 203.0.113.60")
                .exchange().expectStatus().isEqualTo(429);
    }

    @Test
    void a_route_NOT_in_expensive_routes_is_NOT_limited() {
        // The filter is a no-op off the list: we do not want to limit everything.
        for (int i = 0; i < 20; i++) {
            client.get().uri("/api/users/public/legal/terms")
                    .header("X-Forwarded-For", "203.0.113.30")
                    .exchange().expectStatus().isOk();
        }
    }
}
