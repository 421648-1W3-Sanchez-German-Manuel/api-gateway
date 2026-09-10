package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Pipeline step 4 - @Order(3).
 *
 * Marks the exchange as public according to the route convention of §5:
 * `/api/{name}/public/**`. The guards below consume it: they need to know
 * whether a token is required WITHOUT each one re-parsing the path.
 */
@Component
public class PublicRouteGuard implements GlobalFilter, Ordered {

    public static final String ATTR_ES_PUBLICA = "gateway.rutaPublica";

    private final GatewayRoutingProperties props;

    public PublicRouteGuard(GatewayRoutingProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean publica = path.startsWith("/.well-known/")
                || path.startsWith("/fallback/")
                || isPublicServiceRoute(path);
        exchange.getAttributes().put(ATTR_ES_PUBLICA, publica);
        return chain.filter(exchange);
    }

    /** /api/{name}/public/... - the `public` segment is the THIRD one, always. */
    private boolean isPublicServiceRoute(String path) {
        String[] parts = path.split("/");
        // ["", "api", "users", "public", ...]
        return parts.length >= 4
                && props.pathPrefix().equals("/" + parts[1])
                && "public".equals(parts[3]);
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
