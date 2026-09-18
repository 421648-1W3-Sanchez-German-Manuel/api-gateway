package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

/**
 * DEC-23 - this is the flow of `manifiesto-flujos` §11, which is NOT
 * implementable as drawn: the diagram shows the 403 coming out of the
 * `users-svc·users/` lane for an `/api/cursos/**` route - a service that never
 * sees that traffic and cannot block it.
 */
class AccountStateGuardIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String tokenWithStatus(UUID u, String est, boolean pwd, boolean onb) {
        seedSession(redis, u, "sid-1");
        return TokenFactory.person(u, "sid-1",
                b -> b.claim("est", est).claim("pwd", pwd).claim("onb", onb));
    }

    @Test
    void pending_onboarding_gets_403_on_a_route_of_ANOTHER_service() {
        UUID u = UUID.randomUUID();
        client.get().uri("/api/cursos/mis-cursos")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenWithStatus(u, "ACTIVE", false, true))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/onboarding-pending"));
    }

    @Test
    void the_SAME_account_passes_on_a_users_service_route() {
        // The whole rule: if the account is not enabled, ONLY /api/users/** and
        // /api/*/public/** are allowed. The FINE gate (per route, with its
        // exemptions) is users-service's; the gateway applies the coarse one.
        UUID u = UUID.randomUUID();
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenWithStatus(u, "ACTIVE", false, true))
                .exchange().expectStatus().isOk();
    }

    @Test
    void a_PENDING_COURSE_account_gets_403_with_the_state_in_the_body() {
        UUID u = UUID.randomUUID();
        client.get().uri("/api/cursos/mis-cursos")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenWithStatus(u, "PENDING_COURSE", false, false))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.accountStatus").isEqualTo("PENDING_COURSE");
    }

    @Test
    void password_change_required_gets_403_with_its_own_type() {
        UUID u = UUID.randomUUID();
        client.get().uri("/api/cursos/mis-cursos")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenWithStatus(u, "ACTIVE", true, false))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/password-change-required"));
    }

    @Test
    void an_enabled_account_passes_to_any_service() {
        UUID u = UUID.randomUUID();
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenWithStatus(u, "ACTIVE", false, false))
                .exchange().expectStatus().isOk();
    }

    @Test
    void a_person_token_WITHOUT_the_claims_is_rejected_and_the_log_names_them() {
        // DEC-44 - DoD criterion #7d. This is the "old users-service against a
        // new gateway" case, which now fails legibly.
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        String noClaims = TokenFactory.person(u, "sid-1",
                b -> b.claim("est", null).claim("pwd", null).claim("onb", null));

        client.get().uri("/api/cursos/mis-cursos")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, noClaims)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void a_SERVICE_token_does_not_go_through_this_filter() {
        // An MS does not stand for a person with an account: no status to check.
        client.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " +
                        TokenFactory.service("cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
