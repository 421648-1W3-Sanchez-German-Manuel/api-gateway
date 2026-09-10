package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Criterio 7g del DoD · DEC-25 - la cache de sesion de 3 segundos.
 *
 * El riesgo que la cache atiende es DISPONIBILIDAD, no carga: sin ella, un hipo
 * de Redis es una caida del Gateway entero. Pero una cache mal acotada convierte
 * ese beneficio en un agujero: seguir contestando 200 con una sesion que ya no
 * existe es exactamente lo que DEC-22 prohibe.
 *
 * Entonces hay dos afirmaciones, y las dos importan:
 *
 *   1. DENTRO de la ventana, con Redis caido, el request pasa.
 *   2. DESPUES de la ventana, con Redis caido, contesta 503 - nunca 200.
 *
 * Sin la segunda, "cachear" seria "ignorar". Y el 503 tiene que ser 503 y no
 * 401 (DEC-01): decirle "tu sesion vencio" a alguien cuya sesion esta perfecta
 * lo manda a re-loguearse al pedo.
 *
 * Se PAUSA el contenedor, no se para. Pararlo lo devuelve en otro puerto y
 * Spring, que cachea el contexto entre clases, sigue apuntando al viejo: los
 * tests pasan de a uno y fallan corridos. Pausado rechaza conexiones igual pero
 * conserva el mapeo.
 */
class SessionCacheIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void con_Redis_caido_pasa_dentro_de_la_ventana_y_da_503_despues() throws Exception {
        UUID usuario = UUID.randomUUID();
        seedSession(redis, usuario, "sid-1");
        String token = TokenFactory.persona(usuario, "sid-1");

        // Primer request con Redis vivo: es el que llena la cache.
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            // 1) Dentro de los 3s: la cache responde y el request pasa. Esto es
            //    el valor de DEC-25 - Redis se cayo y el Gateway no.
            cliente.get().uri("/api/users/me")
                    .header("Authorization", "Bearer " + token)
                    .exchange().expectStatus().isOk();

            // 2) Pasada la ventana: ya no hay con que verificar, y la respuesta
            //    tiene que ser 503, nunca 200 con una sesion sin verificar.
            Thread.sleep(3500);

            cliente.get().uri("/api/users/me")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().exists("Retry-After");
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void un_logout_se_nota_apenas_expira_la_ventana_y_no_antes() throws Exception {
        // La contracara: la cache retrasa que se note un logout, y ese retraso
        // tiene que estar ACOTADO por el TTL. Si no expirara, cerrar sesion no
        // cerraria nada hasta que el proceso se reinicie.
        UUID usuario = UUID.randomUUID();
        seedSession(redis, usuario, "sid-1");
        String token = TokenFactory.persona(usuario, "sid-1");

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        // Logout: users-service borra la key. Redis sigue vivo.
        clearSession(redis, usuario);

        Thread.sleep(3500);

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
