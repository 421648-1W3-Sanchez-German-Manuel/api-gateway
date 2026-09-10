package ar.edu.utn.frc.tup.p4.apigateway.integration;

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
     * SPEC 7 - 500 ms. Sin esto un Redis que no responde cuelga hasta el
     * default de Lettuce (~60 s), y el WebTestClient corta antes (30 s): el
     * test de {@code con_Redis_DETENIDO} erroraria en vez de ver el 503. En
     * produccion vive en {@code application.yml} bajo
     * {@code spring.data.redis.timeout}.
     */
    @DynamicPropertySource
    static void redisTimeout(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.timeout", () -> "500ms");
    }

    @Test
    void un_sid_desactualizado_da_401_SESION_SUPERADA() {
        // DoD criterion #7. Valid signature and exp, but another device won.
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-B");

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-A"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/session-superseded"));
    }

    @Test
    void la_key_AUSENTE_da_401_SESION_CERRADA_no_503() {
        // DEC-01: tell the cause apart. Absent is a logout, not an outage.
        UUID u = UUID.randomUUID();
        clearSession(redis, u);

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-A"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/session-closed"));
    }

    @Test
    void con_Redis_DETENIDO_responde_503_con_Retry_After_nunca_401_ni_200() {
        // DoD criterion #7b. It is the difference between "your session expired"
        // (a lie, and the user signs in again for nothing) and "come back later".
        //
        // Se PAUSA el contenedor en vez de detenerlo: pausado rechaza igual que
        // caido -que es lo que el test quiere provocar- pero conserva el mapeo
        // de puertos, asi que al despausar el contexto de Spring sigue sirviendo.
        // Con stop()/start() el contenedor vuelve en otro puerto y arrastra a
        // los demas tests de la clase (ver el javadoc de AbstractGatewayTest).
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-A");
        String token = TokenFactory.persona(u, "sid-A");

        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            // Wait for the 3 s cache (DEC-25) to expire before asserting.
            Thread.sleep(3500);
            cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + token)
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
    void un_token_de_SERVICIO_no_pasa_por_el_chequeo_de_sesion() {
        // Service tokens are 100% stateless: no sid, no Redis.
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " +
                        TokenFactory.servicio("cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
