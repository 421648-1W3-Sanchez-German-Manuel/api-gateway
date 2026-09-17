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
 * Development trace: one entry per call the Gateway routes to a micro, origin
 * (person / service) included.
 *
 * It is the raw material of the Logs tab of the development mailbox: with the
 * echo-service demo, THREE entries are encoded in a single operation — the
 * person hitting {@code /api/echo/**}, and echo asking for its service token
 * and calling users-service, always through here.
 *
 * ⛔ Development observability ONLY, and therefore best-effort on purpose:
 * a Redis, serialization or any other failure CANNOT delay or take down a
 * request that already passed the pipeline. The filter records in
 * fire-and-forget (subscribe without waiting), never blocks the event loop and
 * does not touch the response chain.
 *
 * It does not store bodies nor the {@code Authorization}: like the
 * LoggingFilter, a stored token is a token someone can read with a query.
 */
@Component
public class InterMicroTraceFilter implements GlobalFilter, Ordered {

    /** The Redis list that the development mailbox (`/dev/logs`) reads. */
    public static final String REDIS_KEY = "intermicro:trace";
    /** Entry ceiling; the oldest one is discarded with LTRIM. */
    public static final int MAX_ENTRIES = 200;

    private static final Logger log = LoggerFactory.getLogger(InterMicroTraceFilter.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;

    public InterMicroTraceFilter(ReactiveStringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // The route is resolved BEFORE the GlobalFilter chain starts
        // (PipelineOrderIT guarantees it), so the destination is already
        // available right here, whatever status the request ends with.
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String destination = (route == null || route.getUri() == null || route.getUri().getHost() == null)
                ? null : route.getUri().getHost();
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);

        ServerHttpRequest req = exchange.getRequest();
        long start = System.nanoTime();

        return chain.filter(exchange).doFinally(signal -> {
            if (destination == null) {
                // No resolved route means no micro communication to trace.
                return;
            }
            record(exchange, req, destination, jwt, System.nanoTime() - start);
        });
    }

    private void record(ServerWebExchange exchange, ServerHttpRequest req, String destination,
                        Jwt jwt, long nanos) {
        String origin;
        String actor = null;
        if (jwt == null) {
            origin = "ANON";
        } else if ("service".equals(jwt.getClaimAsString("type"))) {
            origin = "MS";
            actor = jwt.getSubject();
        } else {
            origin = "PERSON";
            actor = jwt.getSubject();
        }

        HttpStatusCode status = exchange.getResponse().getStatusCode();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("requestId", req.getHeaders().getFirst(IdentityHeaders.REQUEST_ID));
        entry.put("traceId", req.getHeaders().getFirst("traceparent"));
        entry.put("origen", origin);
        if (actor != null) {
            entry.put("actor", actor);
        }
        entry.put("destino", destination);
        entry.put("metodo", req.getMethod() == null ? "-" : req.getMethod().name());
        entry.put("path", req.getPath().value());
        entry.put("status", status == null ? 0 : status.value());
        entry.put("ms", nanos / 1_000_000);

        String json;
        try {
            json = mapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            log.warn("TRACE_NOT_SERIALIZABLE {}", e.getMessage());
            return;
        }

        redis.opsForList().leftPush(REDIS_KEY, json)
                .then(redis.opsForList().trim(REDIS_KEY, 0, MAX_ENTRIES - 1))
                .onErrorResume(e -> {
                    // Best-effort: the trace never takes down an already resolved request.
                    log.warn("TRACE_STORE_FAILED {}", e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    /** After identity propagation (70) and before the bulkhead (80). */
    @Override
    public int getOrder() {
        return 75;
    }
}
