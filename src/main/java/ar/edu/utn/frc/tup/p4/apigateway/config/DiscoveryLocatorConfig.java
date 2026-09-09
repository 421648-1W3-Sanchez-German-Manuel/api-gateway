package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * No declara rutas dinamicas: las genera {@link AllowlistRouteLocator} a
 * partir de {@code gateway.routing.allowlist}. Esta clase valida la lista en
 * el arranque y expone el derivado serviceId &lt;-&gt; segmento de path.
 *
 * <p>Antes esta generacion la hacia el {@code include-expression} del
 * DiscoveryClient locator. La SpEL {@code serviceId.toLowerCase()} reventaba
 * con {@code EL1004E} en el listener de refresco de rutas - NO en el
 * arranque -, y el Gateway quedaba levantado con la tabla vacia contestando
 * 404 a todo, sin stack trace visible. Mover la generacion a Java elimina
 * la SpEL del locator y deja el error de configuracion donde corresponde:
 * en el arranque.
 */
@Configuration
public class DiscoveryLocatorConfig {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryLocatorConfig.class);

    /** Servicios de infraestructura: rutearlos a traves del Gateway es un bucle. */
    private static final List<String> PROHIBIDOS = List.of("api-gateway", "eureka-server");

    private final GatewayRoutingProperties props;

    public DiscoveryLocatorConfig(GatewayRoutingProperties props) { this.props = props; }

    /** Llamado desde los tests y desde Spring al arrancar. Es publico para eso. */
    @PostConstruct
    public void validateAllowlist() {
        for (String prohibido : PROHIBIDOS) {
            if (props.allowlist().stream()
                    .anyMatch(s -> s.toLowerCase(Locale.ROOT).equals(prohibido))) {
                throw new IllegalStateException(
                        "gateway.routing.allowlist no puede contener '" + prohibido + "': "
                        + "rutear la infraestructura a traves del Gateway produce un bucle.");
            }
        }
        log.info("Allowlist de ruteo: {}", props.allowlist());
    }
}