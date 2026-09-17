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
 * Generates the dynamic routes in Java, reading the typed allowlist of
 * {@link GatewayRoutingProperties}. It replaces the DiscoveryClient locator
 * whose {@code include-expression} evaluates SpEL without method calls
 * (see {@link DiscoveryLocatorConfig} for the detail of the bug this
 * solves).
 *
 * <p>Extra benefits over the dynamic locator:
 * <ul>
 *   <li>A single source for the allowlist - the one the guards and
 *       {@code ServiceAudienceFilter} already consume.</li>
 *   <li>A service on the allowlist but with no instances returns 503 with
 *       {@code Retry-After} (via the breaker's fallback), never 404 - a
 *       404 says "this endpoint does not exist", which is false and sends
 *       you looking for the error where it is not.</li>
 * </ul>
 */
@Configuration
public class AllowlistRouteLocator {

    private final GatewayRoutingProperties props;

    public AllowlistRouteLocator(GatewayRoutingProperties props) { this.props = props; }

    /**
     * Bean that Spring Cloud Gateway consumes to build the route table.
     * It returns a {@code Flux} of definitions, one per serviceId present in
     * the allowlist.
     */
    @Bean
    public RouteDefinitionLocator allowlistRouteDefinitions() {
        List<RouteDefinition> definitions = props.allowlist().stream()
                .map(this::routeFor)
                .toList();
        return () -> Flux.fromIterable(definitions);
    }

    /**
     * users-service -> route with {@code id=allowlist-users-service},
     * {@code uri=lb://users-service} and {@code Path=/api/users/**}.
     * No filters: R7, the path is NOT rewritten.
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
