package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traza de desarrollo: una entrada por llamada que el Gateway enruta a un
 * micro, origen (persona / servicio) incluido.
 *
 * Es la materia prima de la pestaña Logs del buzón de desarrollo: con la
 * demo de echo-service se codifican TRES entradas en una sola operación —
 * la persona pegándole a {@code /api/echo/**}, y echo pidiendo su token de
 * servicio y llamando a users-service, siempre por acá.
 *
 * ⛔ SOLO observabilidad de desarrollo, y por eso es best-effort a propósito:
 * un fallo de Redis, de serialización o de cualquier cosa NO puede atrasar ni
 * tumbar un request que ya pasó el pipeline. El filtro registra en fire-and
 * -forget (subscribe sin espera), nunca bloquea el event loop y no se mete en
 * la cadena de respuesta.
 *
 * No guarda bodies ni el {@code Authorization}: igual que el LoggingFilter,
 * un token almacenado es un token que alguien puede leer con una consulta.
 */
@Component
public class InterMicroTraceFilter implements GlobalFilter, Ordered {

    /** La lista en Redis que lee el buzón de desarrollo (`/dev/logs`). */
    public static final String REDIS_KEY = "intermicro:trace";
    /** Techo de entradas; la más vieja se descarta con LTRIM. */
    public static final int MAX_ENTRADAS = 200;

    private static final Logger log = LoggerFactory.getLogger(InterMicroTraceFilter.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;

    public InterMicroTraceFilter(ReactiveStringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // La ruta se resuelve ANTES de que arranque la cadena de GlobalFilters
        // (lo garantiza PipelineOrderIT), así que el destino ya está disponible
        // acá mismo, sea cual sea el status con el que termine el request.
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String destino = (route == null || route.getUri() == null || route.getUri().getHost() == null)
                ? null : route.getUri().getHost();
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);

        ServerHttpRequest req = exchange.getRequest();
        long inicio = System.nanoTime();

        return chain.filter(exchange).doFinally(señal -> {
            if (destino == null) {
                // Sin ruta resuelta no hay comunicación con un micro que contar.
                return;
            }
            registrar(exchange, req, destino, jwt, System.nanoTime() - inicio);
        });
    }

    private void registrar(ServerWebExchange exchange, ServerHttpRequest req, String destino,
                           Jwt jwt, long nanos) {
        String origen;
        String actor = null;
        if (jwt == null) {
            origen = "ANON";
        } else if ("service".equals(jwt.getClaimAsString("type"))) {
            origen = "MS";
            actor = jwt.getSubject();
        } else {
            origen = "PERSON";
            actor = jwt.getSubject();
        }

        HttpStatusCode status = exchange.getResponse().getStatusCode();
        Map<String, Object> entrada = new LinkedHashMap<>();
        entrada.put("ts", Instant.now().toString());
        entrada.put("requestId", req.getHeaders().getFirst(IdentityHeaders.REQUEST_ID));
        entrada.put("traceId", req.getHeaders().getFirst("traceparent"));
        entrada.put("origen", origen);
        if (actor != null) {
            entrada.put("actor", actor);
        }
        entrada.put("destino", destino);
        entrada.put("metodo", req.getMethod() == null ? "-" : req.getMethod().name());
        entrada.put("path", req.getPath().value());
        entrada.put("status", status == null ? 0 : status.value());
        entrada.put("ms", nanos / 1_000_000);

        String json;
        try {
            json = mapper.writeValueAsString(entrada);
        } catch (JsonProcessingException e) {
            log.warn("TRACE_NO_SERIALIZABLE {}", e.getMessage());
            return;
        }

        redis.opsForList().leftPush(REDIS_KEY, json)
                .then(redis.opsForList().trim(REDIS_KEY, 0, MAX_ENTRADAS - 1))
                .onErrorResume(e -> {
                    // Best-effort: la traza nunca tumba un request ya resuelto.
                    log.warn("TRACE_FALLO_ALMACEN {}", e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    /** Después de la propagación de identidad (70) y antes del bulkhead (80). */
    @Override
    public int getOrder() {
        return 75;
    }
}