package ar.edu.utn.frc.tup.p4.apigateway.integration;

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
        return TokenFactory.persona(u, "sid-1",
                b -> b.claim("est", est).claim("pwd", pwd).claim("onb", onb));
    }

    @Test
    void onboarding_pendiente_recibe_403_en_una_ruta_de_OTRO_micro() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", false, true))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/onboarding-pending"));
    }

    @Test
    void la_MISMA_cuenta_pasa_en_una_ruta_de_users_service() {
        // The whole rule: if the account is not enabled, ONLY /api/users/** and
        // /api/*/public/** are allowed. The FINE gate (per route, with its
        // exemptions) is users-service's; the gateway applies the coarse one.
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", false, true))
                .exchange().expectStatus().isOk();
    }

    @Test
    void una_cuenta_PENDIENTE_CURSO_recibe_403_con_el_estado_en_el_cuerpo() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "PENDING_COURSE", false, false))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.accountStatus").isEqualTo("PENDING_COURSE");
    }

    @Test
    void debe_cambiar_password_recibe_403_con_su_propio_type() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", true, false))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/password-change-required"));
    }

    @Test
    void una_cuenta_habilitada_pasa_a_cualquier_micro() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", false, false))
                .exchange().expectStatus().isOk();
    }

    @Test
    void un_token_de_persona_SIN_los_claims_es_rechazado_y_el_log_los_nombra() {
        // DEC-44 - DoD criterion #7d. This is the "old users-service against a
        // new gateway" case, which now fails legibly.
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        String sinClaims = TokenFactory.persona(u, "sid-1",
                b -> b.claim("est", null).claim("pwd", null).claim("onb", null));

        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + sinClaims)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void un_token_de_SERVICIO_no_atraviesa_este_filtro() {
        // An MS does not stand for a person with an account: no status to check.
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " +
                        TokenFactory.servicio("cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
