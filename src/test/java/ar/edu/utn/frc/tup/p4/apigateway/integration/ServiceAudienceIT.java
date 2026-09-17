package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

/**
 * DEC-04 - damage containment: a token issued to talk to one service does NOT
 * work against another. If a client secret leaks, the blast radius is limited
 * to the destination that token was requested for, instead of being a key to
 * the whole platform.
 */
class ServiceAudienceIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @Test
    void a_token_with_the_CORRECT_aud_passes() {
        client.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.service(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void a_token_with_an_aud_for_ANOTHER_destination_gives_403() {
        client.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.service(
                        "cursos-service", "cursos-service", "users.profile.read"))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/invalid-audience"));
    }

    @Test
    void a_service_token_WITHOUT_aud_gives_403() {
        // Without this a token with no aud would pass everywhere, which is what
        // this filter exists to prevent. There is no permissive default.
        client.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.service(
                        "cursos-service", null, "users.profile.read"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void a_PERSON_token_is_not_affected_by_this_filter() {
        // The annex §7.4 case: Cursos forwards the professor's token to
        // GET /profile/{id}. That token carries NO aud, and it should not:
        // the filter only looks at type: service (DEC-36).
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        client.get().uri("/api/users/profile/x")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.person(u, "sid-1"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void the_aud_is_compared_against_the_RESOLVED_serviceId_not_against_the_path() {
        // Comparing against the path would be fragile: the path is /api/users/...
        // and the serviceId is users-service. The right source is the resolved route.
        client.get().uri("/api/users/something/nested/deep")
                .header("Authorization", "Bearer " + TokenFactory.service(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
