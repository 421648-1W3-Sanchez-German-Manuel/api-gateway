package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.BulkheadProperties;
import ar.edu.utn.frc.tup.p4.apigateway.config.properties.ResilienceProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;

/**
 * Pipeline step 10 - @Order(9) - DEC-42.
 *
 * <p>The missing link in the manifest's chain (§"patrones aplicados"):
 * rate limit -> <b>bulkhead</b> -> timeout -> retry -> circuit breaker -> fallback.
 * Each layer covers a different failure and that is why they all run.
 *
 * <p>One semaphore per destination serviceId. With a single shared pool, a slow
 * destination takes everyone else's requests down with it: a Cursos problem
 * becomes a login outage. With one semaphore per destination, the only one who
 * runs out of permits is the slow destination.
 *
 * <p>It runs after routing -like {@link ServiceAudienceFilter}- because the
 * resolved destination is only known there. It rejects with the same 503 +
 * {@code Retry-After} as the breaker's fallback: for the client "the destination
 * cannot serve you now" is the same situation, whichever layer it comes from.
 */
@Component
public class BulkheadFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BulkheadFilter.class);

    private final BulkheadProperties props;
    private final ResilienceProperties resilience;
    /**
     * One per destination, with EVICTION. Without it, every serviceId that ever
     * receives traffic is an eternal entry: as the allowlist grows, the map
     * only grows. 10 min without use or more than 1000 destinations (well
     * above the real number) and it is evicted; the next request recreates it.
     * Evicting with permits in flight is safe: the in-flight Mono keeps its
     * reference.
     */
    private final Cache<String, Bulkhead> byDestination = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(1000)
            .build();

    public BulkheadFilter(BulkheadProperties props, ResilienceProperties resilience) {
        this.props = props;
        this.resilience = resilience;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.enabled()) return chain.filter(exchange);

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || route.getUri().getHost() == null) {
            // No resolved destination means nothing to limit. That is not a
            // reason to reject: the request can still be the JWKS or the
            // fallback.
            return chain.filter(exchange);
        }
        String destination = route.getUri().getHost().toLowerCase(Locale.ROOT);

        return chain.filter(exchange)
                .transformDeferred(BulkheadOperator.of(bulkheadFor(destination)))
                .onErrorResume(BulkheadFullException.class, e -> {
                    log.warn("BULKHEAD_FULL destination={} maxConcurrentCalls={}",
                            destination, props.maxConcurrentCalls());
                    return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                            ErrorTypes.SERVICE_UNAVAILABLE, "Service unavailable",
                            "The service '" + destination + "' is saturated. Retry in a few seconds.",
                            resilience.bulkheadRetryAfter());
                });
    }

    /** One per destination, created the first time that destination receives traffic. */
    private Bulkhead bulkheadFor(String destination) {
        return byDestination.get(destination, id -> Bulkhead.of(id, BulkheadConfig.custom()
                .maxConcurrentCalls(props.maxConcurrentCalls())
                .maxWaitDuration(props.maxWait() == null ? Duration.ZERO : props.maxWait())
                .build()));
    }

    @Override public int getOrder() { return 90; }
}
