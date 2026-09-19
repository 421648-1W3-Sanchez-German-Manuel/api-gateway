package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

class SessionInvalidationIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    /**
     * SPEC 7 - 500 ms. Without this, a Redis that does not answer hangs until
     * the Lettuce default (~60 s), and the WebTestClient cuts earlier (30 s):
     * the {@code with_Redis_PAUSED} test would error out instead of seeing the
     * 503. In production it lives in {@code application.yml} under
     * {@code spring.data.redis.timeout}.
     */
    @DynamicPropertySource
    static void redisTimeout(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.timeout", () -> "500ms");
    }

    @Test
    void an_outdated_sid_gives_401_SESSION_SUPERSEDED() {
        // DoD criterion #7. Valid signature and exp, but another device won.
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-B");

        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.person(u, "sid-A"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/session-superseded"));
    }

    /**
     * The mirror of the test above, and the one that was missing: the SAME
     * superseded cookie, on a PUBLIC route, must pass.
     *
     * <p>The browser attaches {@code fu_at} to {@code /api/*}/public/**} merely
     * because it shares the origin — not by the frontend's choice, as it used
     * to be when the token travelled in a header. So the cookie of an already
     * superseded session arrives at the very endpoint that exists to CREATE a
     * new one, and rejecting it locks the person out of logging back in: the
     * only way forward is the request the guard is refusing.
     *
     * <p>SessionGuard skips public routes for exactly that reason, but the skip
     * read {@code PublicRouteGuard.ATTR_IS_PUBLIC} — an attribute written by a
     * GlobalFilter, while SessionGuard is a WebFilter. The whole WebFilter phase
     * runs before the first GlobalFilter, so it was always null and the skip
     * never fired.
     *
     * <p>No unit test could catch it: the ordering only exists once the real
     * filter chains are assembled. This one sends the cookie over HTTP through
     * the complete pipeline, which is the only place the bug was visible.
     */
    @Test
    void a_PUBLIC_route_passes_WITH_a_superseded_cookie() {
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-B");

        client.post().uri("/api/users/public/auth/login")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.person(u, "sid-A"))
                .exchange()
                // The destination answers; the gateway does not cut it. Whatever
                // users-service replies to the login is its business — what this
                // pins is that the request GOT there.
                .expectStatus().isOk();
    }

    @Test
    void an_ABSENT_key_gives_401_SESSION_CLOSED_not_503() {
        // DEC-01: tell the cause apart. Absent is a logout, not an outage.
        UUID u = UUID.randomUUID();
        clearSession(redis, u);

        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.person(u, "sid-A"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/session-closed"));
    }

    @Test
    void with_Redis_PAUSED_it_answers_503_with_Retry_After_never_401_nor_200() {
        // DoD criterion #7b. It is the difference between "your session expired"
        // (a lie, and the user signs in again for nothing) and "come back later".
        //
        // The container is PAUSED instead of stopped: paused it rejects just
        // like down - which is what the test wants to provoke - but keeps the
        // port mapping, so on unpause the Spring context keeps serving. With
        // stop()/start() the container comes back on another port and drags
        // down the rest of the tests in the class (see AbstractGatewayTest's
        // javadoc).
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-A");
        String token = TokenFactory.person(u, "sid-A");

        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            // Wait for the 3 s cache (DEC-25) to expire before asserting.
            Thread.sleep(3500);
            client.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, token)
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().exists("Retry-After");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    void a_SERVICE_token_does_not_go_through_the_session_check() {
        // Service tokens are 100% stateless: no sid, no Redis.
        client.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " +
                        TokenFactory.service("cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
