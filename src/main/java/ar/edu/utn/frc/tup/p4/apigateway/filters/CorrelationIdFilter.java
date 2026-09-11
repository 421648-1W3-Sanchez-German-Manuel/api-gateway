package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Paso 2 del pipeline · @Order(10).
 *
 * An INCOMING `traceparent` is accepted (W3C Trace Context): if another service
 * started the trace, overwriting it splits the trail in two right at the edge.
 * That is why `traceparent` is NOT in `remove-request-headers` in
 * application.yml, unlike the five identity headers.
 *
 * The context propagates through the **Reactor context**, not MDC/ThreadLocal:
 * in WebFlux a request hops between threads and a ThreadLocal is lost.
 *
 * The `traceId` and `spanId` derive from the `traceparent` — the same value
 * travels downstream and is what the microservices put in their logs
 * (RequestLogFilter in users-service). This filter publishes all three ids into
 * the Reactor context; CorrelationMdcConfig turns them into MDC around each
 * signal, so the logback pattern prints them in the gateway's own line.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CTX_REQUEST_ID = "requestId";
    public static final String CTX_TRACE_ID = "traceId";
    public static final String CTX_SPAN_ID = "spanId";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = valueOrGenerated(
                exchange.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID),
                () -> UUID.randomUUID().toString());
        String traceparent = valueOrGenerated(
                exchange.getRequest().getHeaders().getFirst("traceparent"),
                CorrelationIdFilter::newTraceparent);

        ServerWebExchange mutado = exchange.mutate()
                .request(r -> r.header(IdentityHeaders.REQUEST_ID, requestId)
                               .header("traceparent", traceparent))
                .build();

        // Send it back in the response: it is the id a user can report.
        mutado.getResponse().getHeaders().set(IdentityHeaders.REQUEST_ID, requestId);

        return chain.filter(mutado)
                .contextWrite(Context.of(
                        CTX_REQUEST_ID, requestId,
                        CTX_TRACE_ID, traceparentTraceId(traceparent),
                        CTX_SPAN_ID, traceparentSpanId(traceparent)));
    }

    /** W3C Trace Context: `00-{traceId 32 hex}-{spanId 16 hex}-{flags}`. */
    private static String traceparentTraceId(String traceparent) {
        return parte(traceparent, 1, 32);
    }

    private static String traceparentSpanId(String traceparent) {
        return parte(traceparent, 2, 16);
    }

    private static String parte(String traceparent, int indice, int largo) {
        if (traceparent == null) return "";
        String[] parts = traceparent.split("-");
        if (parts.length < 3) return "";
        String p = parts[indice];
        if (p.length() != largo || !p.matches("[0-9a-fA-F]{" + largo + "}")) return "";
        return p;
    }

    private static String newTraceparent() {
        byte[] trace = new byte[16];
        byte[] span = new byte[8];
        RANDOM.nextBytes(trace);
        RANDOM.nextBytes(span);
        HexFormat hex = HexFormat.of();
        return "00-" + hex.formatHex(trace) + "-" + hex.formatHex(span) + "-01";
    }

    private String valueOrGenerated(String entrante, java.util.function.Supplier<String> generador) {
        return (entrante == null || entrante.isBlank()) ? generador.get() : entrante;
    }

    @Override public int getOrder() { return 10; }
}
