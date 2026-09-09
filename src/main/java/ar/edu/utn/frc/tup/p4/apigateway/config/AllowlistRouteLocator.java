package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;

/**
 * Genera las rutas dinamicas en Java, leyendo la allowlist tipada de
 * {@link GatewayRoutingProperties}. Reemplaza al DiscoveryClient locator
 * cuya {@code include-expression} evalua SpEL sin llamadas a metodo
 * (ver {@link DiscoveryLocatorConfig} para el detalle del bug que esto
 * resuelve).
 *
 * <p>Beneficios extra respecto del locator dinamico:
 * <ul>
 *   <li>Una sola fuente para la allowlist - la que ya consumen los guards y
 *       {@code ServiceAudienceFilter}.</li>
 *   <li>Un servicio en la allowlist pero sin instancias devuelve 503 con
 *       {@code Retry-After} (vía el fallback del breaker), nunca 404 - un
 *       404 dice "este endpoint no existe", que es falso y manda a buscar
 *       el error donde no esta.</li>
 * </ul>
 */
@Configuration
public class AllowlistRouteLocator {

    private final GatewayRoutingProperties props;

    public AllowlistRouteLocator(GatewayRoutingProperties props) { this.props = props; }

    /**
     * Bean que Spring Cloud Gateway consume para armar la tabla de rutas.
     * Devuelve un {@code Flux} de definiciones, una por cada serviceId
     * presente en la allowlist.
     */
    @Bean
    public RouteDefinitionLocator allowlistRouteDefinitions() {
        List<RouteDefinition> definiciones = props.allowlist().stream()
                .map(this::routeFor)
                .toList();
        return () -> Flux.fromIterable(definiciones);
    }

    /**
     * users-service -> ruta con {@code id=allowlist-users-service},
     * {@code uri=lb://users-service} y {@code Path=/api/users/**}.
     * Sin filtros: R7, el path NO se reescribe.
     */
    private RouteDefinition routeFor(String serviceId) {
        RouteDefinition def = new RouteDefinition();
        def.setId("allowlist-" + serviceId);
        def.setUri(URI.create("lb://" + serviceId));
        def.setPredicates(List.of(new PredicateDefinition(
                "Path=" + props.pathPrefix() + "/" + props.serviceIdToPathSegment(serviceId) + "/**")));
        return def;
    }
}