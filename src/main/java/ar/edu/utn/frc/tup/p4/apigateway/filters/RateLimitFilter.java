package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.RateLimitKeyResolver;
import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.TokenBucket;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Paso 9 del pipeline · @Order(8) · condicional.
 *
 * A no-op for every route that does not match `expensive-routes`. Startup FAILS
 * if an entry is configured with a threshold of 0: a route declared expensive
 * with zero budget rejects everything, which is worse than not configuring it.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimitProperties props;
    private final RateLimitKeyResolver resolve;
    private final TokenBucket bucket;

    public RateLimitFilter(RateLimitProperties props, RateLimitKeyResolver resolve, TokenBucket bucket) {
        this.props = props;
        this.resolve = resolve;
        this.bucket = bucket;
        validateThresholds();
    }

    private void validateThresholds() {
        if (props.expensiveRoutes() == null) return;
        props.expensiveRoutes().forEach(r -> {
            if (r.capacity() <= 0 || r.refillPerMinute() <= 0) {
                throw new IllegalStateException(
                        "La ruta cara '" + r.path() + "' tiene umbral 0: rechazaria todo. "
                        + "Configurar capacity y refill-per-minute, o sacarla de la lista.");
            }
        });
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.enabled() || props.expensiveRoutes() == null) return chain.filter(exchange);

        String path = exchange.getRequest().getPath().value();
        var cara = props.expensiveRoutes().stream()
                .filter(r -> MATCHER.match(r.path(), path)).findFirst();
        if (cara.isEmpty()) return chain.filter(exchange);

        var ruta = cara.get();
        String key = ruta.path() + "|" + resolve.resolve(exchange, ruta.key());

        if (bucket.consume(key, ruta.capacity(), ruta.refillPerMinute())) {
            return chain.filter(exchange);
        }
        // DEC-24 - the SAME type auth/ returns for its per-e-mail limit.
        return ProblemDetails.withRetryAfter(exchange, HttpStatus.TOO_MANY_REQUESTS,
                ErrorTypes.TOO_MANY_ATTEMPTS, "Demasiados intentos",
                "Superó el limite de solicitudes. Reintente mas tarde.",
                bucket.suggestedWait(key));
    }

    @Override public int getOrder() { return 80; }
}
