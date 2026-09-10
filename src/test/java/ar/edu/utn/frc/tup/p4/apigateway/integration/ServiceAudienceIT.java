package ar.edu.utn.frc.tup.p4.apigateway.integration;

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
    void un_token_con_aud_CORRECTO_pasa() {
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void un_token_con_aud_de_OTRO_destino_da_403() {
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "cursos-service", "users.profile.read"))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/invalid-audience"));
    }

    @Test
    void un_token_de_servicio_SIN_aud_da_403() {
        // Without this a token with no aud would pass everywhere, which is what
        // this filter exists to prevent. There is no permissive default.
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", null, "users.profile.read"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void un_token_de_PERSONA_no_es_afectado_por_este_filtro() {
        // The annex §7.4 case: Cursos forwards the professor's token to
        // GET /profile/{id}. That token carries NO aud, and it should not:
        // the filter only looks at type: service (DEC-36).
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-1"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void el_aud_se_compara_contra_el_serviceId_RESUELTO_no_contra_el_path() {
        // Comparing against the path would be fragile: the path is /api/users/...
        // and the serviceId is users-service. The right source is the resolved route.
        cliente.get().uri("/api/users/algo/anidado/profundo")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
