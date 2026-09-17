package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

/**
 * The prefixes exempt from the coarse gate are CONFIG, not code: with another
 * list, another account gets into other services without touching the gateway.
 */
@TestPropertySource(properties = {
        "gateway.account-gate.exempt-prefixes[0]=/api/cursos/**"
})
class AccountGateExemptIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String pending(UUID u) {
        seedSession(redis, u, "sid-1");
        return TokenFactory.person(u, "sid-1",
                b -> b.claim("est", "PENDING_COURSE").claim("pwd", false).claim("onb", false));
    }

    @Test
    void with_another_config_the_not_enabled_account_passes_the_exempt_prefix() {
        UUID u = UUID.randomUUID();
        client.get().uri("/api/cursos/mis-cursos")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, pending(u))
                .exchange().expectStatus().isOk();
    }

    @Test
    void and_it_stays_blocked_outside_the_exempt_prefix() {
        UUID u = UUID.randomUUID();
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, pending(u))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.accountStatus").isEqualTo("PENDING_COURSE");
    }
}
