package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

class ResilienceIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String token() {
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        return TokenFactory.persona(u, "sid-1");
    }

    @AfterEach
    void resetDestination() {
        DESTINO.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });
    }

    @Test
    void with_the_destination_DOWN_the_breaker_opens_and_answers_via_the_fallback() {
        DESTINO.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(500);
            }
        });

        String t = token();
        // Fill the breaker's window (slidingWindowSize = 20).
        for (int i = 0; i < 25; i++) {
            cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + t).exchange();
        }

        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + t)
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.type").value(v ->
                        org.assertj.core.api.Assertions.assertThat((String) v)
                                .endsWith("/service-unavailable"));
    }

    @Test
    void a_SLOW_destination_cuts_by_timeout_before_the_client_does() {
        // DEC-42 - timeoutDuration 3 s < a browser's typical timeout. If the
        // gateway cut later, it would keep a thread busy for a response nobody
        // is going to read any more.
        DESTINO.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(200)
                        .setBodyDelay(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        });

        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + token())
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void the_fallback_returns_a_ProblemDetail_not_an_error_page() {
        cliente.get().uri("/fallback/users-service")
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType("application/problem+json");
    }
}
