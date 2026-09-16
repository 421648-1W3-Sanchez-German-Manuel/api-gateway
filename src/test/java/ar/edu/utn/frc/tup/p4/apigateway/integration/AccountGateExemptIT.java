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
 * Los prefijos exentos del gate grueso son CONFIG, no codigo: con otra lista,
 * otra cuenta pasa a otros micros sin tocar el gateway.
 */
@TestPropertySource(properties = {
        "gateway.account-gate.exempt-prefixes[0]=/api/cursos/**"
})
class AccountGateExemptIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String pendiente(UUID u) {
        seedSession(redis, u, "sid-1");
        return TokenFactory.persona(u, "sid-1",
                b -> b.claim("est", "PENDING_COURSE").claim("pwd", false).claim("onb", false));
    }

    @Test
    void con_otra_config_la_cuenta_no_habilitada_pasa_al_prefijo_exento() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/cursos/mis-cursos")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, pendiente(u))
                .exchange().expectStatus().isOk();
    }

    @Test
    void y_sigue_bloqueada_fuera_del_prefijo_exento() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, pendiente(u))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.accountStatus").isEqualTo("PENDING_COURSE");
    }
}
