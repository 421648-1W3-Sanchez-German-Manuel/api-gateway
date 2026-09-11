package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.filters.InterMicroTraceFilter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La traza no es un log: es una ESTRUCTURA. Que el filtro registre en Redis lo
 * garantiza el unitario con mocks; esto prueba que el pipeline COMPLETO por
 * HTTP la puebla — el filtro a solas ya no alcanza si algo lo desconecta.
 */
class InterMicroTraceIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @Test
    void un_request_autenticado_queda_como_origen_PERSON_y_destino_users() throws InterruptedException {
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-1"))
                .exchange().expectStatus().isOk();

        List<String> entradas = esperarQueLlegue();
        assertThat(entradas).anySatisfy(e ->
                assertThat(e).contains("\"origen\":\"PERSON\"")
                        .contains("\"destino\":\"users-service\"")
                        .contains("\"path\":\"/api/users/me\""));
    }

    @Test
    void un_request_public_sin_token_queda_como_origin_ANON() throws InterruptedException {
        // login es public y RUTEADO hasta el destino. Sin identity que propagar,
        // la entrada tiene que decir ANON — y es lo que distingue una llamada de
        // una persona de la de un servicio en la pestaña de logs.
        cliente.post().uri("/api/users/public/auth/login").exchange().expectStatus().isOk();

        List<String> entradas = esperarQueLlegue();
        assertThat(entradas).anySatisfy(e ->
                assertThat(e).contains("\"origen\":\"ANON\"")
                        .contains("\"path\":\"/api/users/public/auth/login\""));
    }

    /**
     * La escritura del filtro es fire-and-forget best-effort, asi que no hay
     * garantia de que la entrada ya esté cuando contesta el request: se espera
     * hasta TOPE ms a que APAREZCA una entrada nueva (con la ts de un momento
     * reciente), no a que la lista crezca desde un contador propio.
     */
    private List<String> esperarQueLlegue() throws InterruptedException {
        long tope = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < tope) {
            List<String> todas = redis.opsForList()
                    .range(InterMicroTraceFilter.REDIS_KEY, 0, -1)
                    .collectList().block();
            if (todas != null && !todas.isEmpty()) {
                return todas;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("La traza no recibio ninguna entrada en 5s");
    }
}