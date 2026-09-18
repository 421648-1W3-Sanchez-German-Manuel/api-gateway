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
 * Pipeline step 2 · @Order(10).
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
 *
 * <p>Both incoming ids are UNTRUSTED input: they go into the response headers,
 * the MDC (logs) and downstream. Anything that is not shaped like an id is
 * discarded and regenerated — accepting it verbatim allows log-line injection
 * and breaks trace correlation with a malformed `traceparent`.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CTX_REQUEST_ID = "requestId";
    public static final String CTX_TRACE_ID = "traceId";
    public static final String CTX_SPAN_ID = "spanId";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * What an incoming {@code X-Request-Id} may look like. Letters, digits and
     * {@code . _ : -}, up to 128 chars: enough for any UUID/trace id, useless
     * for a log-injection payload (no whitespace, no quotes, no newlines).
     */
    private static final java.util.regex.Pattern REQUEST_ID_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /** Strict W3C Trace Context: version 00, lowercase hex, flags 00/01. */
    private static final java.util.regex.Pattern TRACEPARENT_PATTERN =
            java.util.regex.Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = validOrGenerated(
                exchange.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID),
                REQUEST_ID_PATTERN, () -> UUID.randomUUID().toString());
        String traceparent = validOrGenerated(
                exchange.getRequest().getHeaders().getFirst("traceparent"),
                TRACEPARENT_PATTERN, CorrelationIdFilter::newTraceparent);

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header(IdentityHeaders.REQUEST_ID, requestId)
                               .header("traceparent", traceparent))
                .build();

        // Send it back in the response: it is the id a user can report.
        mutated.getResponse().getHeaders().set(IdentityHeaders.REQUEST_ID, requestId);

        return chain.filter(mutated)
                .contextWrite(Context.of(
                        CTX_REQUEST_ID, requestId,
                        CTX_TRACE_ID, traceparentTraceId(traceparent),
                        CTX_SPAN_ID, traceparentSpanId(traceparent)));
    }

    /** W3C Trace Context: `00-{traceId 32 hex}-{spanId 16 hex}-{flags}`. */
    private static String traceparentTraceId(String traceparent) {
        return part(traceparent, 1, 32);
    }

    private static String traceparentSpanId(String traceparent) {
        return part(traceparent, 2, 16);
    }

    private static String part(String traceparent, int index, int length) {
        if (traceparent == null) return "";
        String[] parts = traceparent.split("-");
        if (parts.length < 3) return "";
        String p = parts[index];
        if (p.length() != length || !p.matches("[0-9a-fA-F]{" + length + "}")) return "";
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

    private String validOrGenerated(String incoming, java.util.regex.Pattern pattern,
                                        java.util.function.Supplier<String> generator) {
        if (incoming == null || incoming.isBlank()) {
            return generator.get();
        }
        // Malformed or hostile: regenerate. The value is NOT logged as-is —
        // it would be the injection itself — only its shape is worth knowing.
        if (!pattern.matcher(incoming).matches()) {
            return generator.get();
        }
        return incoming;
    }

    @Override public int getOrder() { return 10; }
}
