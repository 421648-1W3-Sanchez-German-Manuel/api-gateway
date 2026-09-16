package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

    private String token() {
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        return TokenFactory.persona(u, "sid-1");
    }

    /**
     * Spring caches the ApplicationContext across test classes with identical
     * config, so the "porServicio" breaker is the SAME instance every other IT
     * class sees. Without this reset, a test here that deliberately opens it
     * leaves every later class (ServiceAudienceIT, AccountStateGuardIT, ...)
     * getting 503 from an open breaker that has nothing to do with them.
     */
    @AfterEach
    void resetDestinationAndBreaker() {
        DESTINO.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });
        circuitBreakerRegistry.circuitBreaker("porServicio").reset();
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
            cliente.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, t).exchange();
        }

        cliente.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, t)
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
                // setHeadersDelay, not setBodyDelay: NettyRoutingFilter commits
                // the response status as soon as headers arrive, and a committed
                // response can no longer be swapped for the fallback. Delaying
                // only the body means the client sees 200 fast and just waits
                // out the slow body - the breaker's timeout never gets a chance
                // to redirect anything. Delaying the headers keeps the response
                // uncommitted until the CircuitBreaker's TimeLimiter can act.
                return new MockResponse().setResponseCode(200).setBody("ok")
                        .setHeadersDelay(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        });

        cliente.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token())
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void el_fallback_invocado_DIRECTO_da_404_no_503() {
        // El fallback solo existe como destino del forward del breaker. Sin
        // ruta resuelta no hay destino caido que reportar: un 503 directo
        // permite enumerar servicios y ensucia el monitoreo con caidas falsas.
        cliente.get().uri("/fallback/users-service")
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").value(v ->
                        org.assertj.core.api.Assertions.assertThat((String) v)
                                .endsWith("/route-not-found"));
    }

    @Test
    void el_fallback_via_breaker_sigue_dando_503_con_ProblemDetail() {
        DESTINO.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(500);
            }
        });
        for (int i = 0; i < 25; i++) {
            cliente.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token()).exchange();
        }
        cliente.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token())
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType("application/problem+json");
    }
}
