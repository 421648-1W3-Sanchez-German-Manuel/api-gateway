package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * DoD criterion 7g · DEC-25 - the 3 second session cache.
 *
 * The risk the cache addresses is AVAILABILITY, not load: without it, a Redis
 * hiccup is a whole Gateway outage. But a poorly bounded cache turns that
 * benefit into a hole: keeping on answering 200 with a session that no longer
 * exists is exactly what DEC-22 forbids.
 *
 * So there are two claims, and both matter:
 *
 *   1. INSIDE the window, with Redis down, the request passes.
 *   2. AFTER the window, with Redis down, it answers 503 - never 200.
 *
 * Without the second, "caching" would be "ignoring". And the 503 has to be a
 * 503 and not a 401 (DEC-01): telling someone whose session is perfectly fine
 * "your session expired" sends them to log in again for nothing.
 *
 * The container is PAUSED, not stopped. Stopping it brings it back on another
 * port and Spring, which caches the context between classes, keeps pointing at
 * the old one: the tests pass one by one and fail when run together. Paused, it
 * rejects connections just the same but keeps the mapping.
 */
class SessionCacheIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void with_Redis_down_it_passes_inside_the_window_and_gives_503_afterwards() throws Exception {
        UUID user = UUID.randomUUID();
        seedSession(redis, user, "sid-1");
        String token = TokenFactory.person(user, "sid-1");

        // First request with Redis alive: this is the one that fills the cache.
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token)
                .exchange().expectStatus().isOk();

        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            // 1) Inside the 3s: the cache answers and the request passes. This is
            //    the value of DEC-25 - Redis went down and the Gateway did not.
            client.get().uri("/api/users/me")
                    .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token)
                    .exchange().expectStatus().isOk();

            // 2) Past the window: there is nothing left to verify with, and the
            //    response has to be 503, never 200 with an unverified session.
            Thread.sleep(3500);

            client.get().uri("/api/users/me")
                    .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token)
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().exists("Retry-After");
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void a_logout_is_noticed_only_after_the_window_expires_and_not_before() throws Exception {
        // The flip side: the cache delays a logout from being noticed, and that
        // delay has to be BOUNDED by the TTL. If it did not expire, logging out
        // would close nothing until the process restarted.
        UUID user = UUID.randomUUID();
        seedSession(redis, user, "sid-1");
        String token = TokenFactory.person(user, "sid-1");

        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token)
                .exchange().expectStatus().isOk();

        // Logout: users-service deletes the key. Redis stays alive.
        clearSession(redis, user);

        Thread.sleep(3500);

        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
