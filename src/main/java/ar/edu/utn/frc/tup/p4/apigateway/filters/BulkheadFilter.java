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
 * <p>El eslabon que faltaba de la cadena del manifiesto (§"patrones aplicados"):
 * rate limit -> <b>bulkhead</b> -> timeout -> retry -> circuit breaker -> fallback.
 * Cada capa cubre una falla distinta y por eso van todas.
 *
 * <p>Un semaforo por serviceId destino. Con un solo pool compartido, un destino
 * lento se lleva puestas las requests de los demas: el problema de Cursos se
 * convierte en una caida de login. Con un semaforo por destino, el que se
 * queda sin permisos es solo el destino lento.
 *
 * <p>Corre despues del ruteo -igual que {@link ServiceAudienceFilter}- porque
 * recien ahi se conoce el destino resuelto. Rechaza con el mismo 503 +
 * {@code Retry-After} que el fallback del breaker: para el cliente "el destino
 * no te puede atender ahora" es la misma situacion, la venga de donde venga.
 */
@Component
public class BulkheadFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BulkheadFilter.class);

    private final BulkheadProperties props;
    private final ResilienceProperties resiliencia;
    /**
     * Uno por destino, con EVICCION. Sin esto cada serviceId que alguna vez
     * recibe trafico es una entrada eterna: con la allowlist creciendo, el
     * mapa solo crece. 10 min sin uso o mas de 1000 destinos (muy por encima
     * de lo real) y se evicta; el siguiente request lo recrea. Evictar con
     * permisos en vuelo es seguro: el Mono en curso retiene su referencia.
     */
    private final Cache<String, Bulkhead> porDestino = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(1000)
            .build();

    public BulkheadFilter(BulkheadProperties props, ResilienceProperties resiliencia) {
        this.props = props;
        this.resiliencia = resiliencia;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.enabled()) return chain.filter(exchange);

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || route.getUri().getHost() == null) {
            // Sin destino resuelto no hay a que destino limitar. No es motivo
            // para rechazar: la request todavia puede ser el JWKS o el fallback.
            return chain.filter(exchange);
        }
        String destino = route.getUri().getHost().toLowerCase(Locale.ROOT);

        return chain.filter(exchange)
                .transformDeferred(BulkheadOperator.of(bulkheadDe(destino)))
                .onErrorResume(BulkheadFullException.class, e -> {
                    log.warn("BULKHEAD_LLENO destino={} maxConcurrentCalls={}",
                            destino, props.maxConcurrentCalls());
                    return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                            ErrorTypes.SERVICE_UNAVAILABLE, "Servicio no disponible",
                            "El servicio '" + destino + "' esta saturado. Reintente en unos segundos.",
                            resiliencia.bulkheadRetryAfter());
                });
    }

    /** Uno por destino, creado la primera vez que ese destino recibe trafico. */
    private Bulkhead bulkheadDe(String destino) {
        return porDestino.get(destino, id -> Bulkhead.of(id, BulkheadConfig.custom()
                .maxConcurrentCalls(props.maxConcurrentCalls())
                .maxWaitDuration(props.maxWait() == null ? Duration.ZERO : props.maxWait())
                .build()));
    }

    @Override public int getOrder() { return 90; }
}
