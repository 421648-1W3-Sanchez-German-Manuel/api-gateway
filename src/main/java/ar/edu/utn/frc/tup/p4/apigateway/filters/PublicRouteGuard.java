package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.routing.PublicRouteMatcher;
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

    private final PublicRouteMatcher matcher;

    public PublicRouteGuard(PublicRouteMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean publica = matcher.esPublica(exchange.getRequest().getPath().value());
        exchange.getAttributes().put(ATTR_ES_PUBLICA, publica);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
