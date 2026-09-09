package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.config.DiscoveryLocatorConfig;
import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3 - ruteo dinamico gobernado por la allowlist tipada. Cubre:
 *  - R7 · el path NO se reescribe (DoD #2);
 *  - DoD #3 · un servicio FUERA de la allowlist no es accesible;
 *  - la derivacion serviceId &lt;-&gt; segmento de path que la spec fija;
 *  - la validacion de arranque que rechaza la allowlist si incluye infra.
 *
 * <p>Sin {@code AllowlistRouteLocator} las rutas se generaban via el
 * {@code include-expression} del DiscoveryClient locator, y la SpEL
 * {@code serviceId.toLowerCase()} reventaba en el listener de refresco, NO
 * en el arranque: el Gateway levantaba sano, con la tabla vacia, y daba 404
 * a todo. Estos tests documentan el contrato que la nueva generacion de
 * rutas debe cumplir.
 */
class DiscoveryAllowlistIT extends AbstractGatewayTest {

    @Test
    void el_path_llega_AL_DESTINO_SIN_REESCRIBIR() throws Exception {
        // R7 - DoD #2. El destino recibe /api/users/me, no /me.
        // Si alguien agrega un RewritePath "para limpiar el prefijo", este
        // test lo caza: los controllers del destino estan mapeados CON prefijo.
        UUID sub = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(sub, "sid-1"))
                .exchange();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getPath()).isEqualTo("/api/users/me");
    }

    @Test
    void un_servicio_FUERA_de_la_allowlist_responde_404() {
        // DoD #3. Registrarse en Eureka NO expone un servicio: hasta que no
        // esta en la allowlist tipada, no existe para el exterior.
        cliente.get().uri("/api/otro/lo-que-sea")
                .header("Authorization", "Bearer " + TokenFactory.persona(UUID.randomUUID(), "s"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void la_derivacion_de_serviceId_a_segmento_es_la_de_la_spec() {
        var props = new GatewayRoutingProperties(List.of("users-service"), "-service", "/api");
        assertThat(props.serviceIdToPathSegment("users-service")).isEqualTo("users");
        assertThat(props.serviceIdToPathSegment("USERS-SERVICE")).isEqualTo("users");
        assertThat(props.serviceIdToPathSegment("cursos-service")).isEqualTo("cursos");
    }

    @Test
    void el_arranque_FALLA_si_la_allowlist_incluye_al_propio_gateway() {
        // Rutearse a si mismo produce un bucle infinito que aparece como stack
        // overflow o timeout, nunca como un error legible.
        var props = new GatewayRoutingProperties(
                List.of("users-service", "api-gateway"), "-service", "/api");
        assertThatThrownBy(() -> new DiscoveryLocatorConfig(props).validateAllowlist())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-gateway");
    }

    @Test
    void el_arranque_FALLA_si_la_allowlist_incluye_a_eureka() {
        var props = new GatewayRoutingProperties(
                List.of("users-service", "eureka-server"), "-service", "/api");
        assertThatThrownBy(() -> new DiscoveryLocatorConfig(props).validateAllowlist())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eureka-server");
    }
}