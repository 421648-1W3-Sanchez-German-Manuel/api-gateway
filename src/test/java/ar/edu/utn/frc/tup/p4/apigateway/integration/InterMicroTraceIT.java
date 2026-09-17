package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.filters.InterMicroTraceFilter;
import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace is not a log: it is a STRUCTURE. That the filter writes to Redis is
 * guaranteed by the unit test with mocks; this proves that the COMPLETE
 * pipeline over HTTP fills it - the filter alone is not enough if something
 * disconnects it.
 */
class InterMicroTraceIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @Test
    void an_authenticated_request_is_recorded_as_origin_PERSON_and_destination_users() throws InterruptedException {
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.person(u, "sid-1"))
                .exchange().expectStatus().isOk();

        List<String> entries = waitForItToArrive();
        assertThat(entries).anySatisfy(e ->
                assertThat(e).contains("\"origen\":\"PERSON\"")
                        .contains("\"destino\":\"users-service\"")
                        .contains("\"path\":\"/api/users/me\""));
    }

    @Test
    void a_public_request_without_a_token_is_recorded_as_origin_ANON() throws InterruptedException {
        // login is public and ROUTED to the destination. With no identity to
        // propagate, the entry has to say ANON — and that is what tells a
        // person's call apart from a service's in the logs tab.
        client.post().uri("/api/users/public/auth/login").exchange().expectStatus().isOk();

        List<String> entries = waitForItToArrive();
        assertThat(entries).anySatisfy(e ->
                assertThat(e).contains("\"origen\":\"ANON\"")
                        .contains("\"path\":\"/api/users/public/auth/login\""));
    }

    /**
     * The filter's write is fire-and-forget best-effort, so there is no
     * guarantee that the entry is already there when the request answers: it
     * waits up to CAP ms for a NEW entry to APPEAR (with the ts of a recent
     * moment), not for the list to grow from a counter of its own.
     */
    private List<String> waitForItToArrive() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<String> all = redis.opsForList()
                    .range(InterMicroTraceFilter.REDIS_KEY, 0, -1)
                    .collectList().block();
            if (all != null && !all.isEmpty()) {
                return all;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("The trace received no entries within 5s");
    }
}
