package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ONLY way the Gateway cuts a request. A filter writing its own response
 * body breaks the uniformity of the error contract, and the frontend ends up
 * with one handling branch per filter.
 */
public final class ProblemDetails {

    /** Exchange attribute where a filter leaves extra keys for the body. */
    public static final String ATTR_EXTRAS = "gateway.problemExtras";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                      URI type, String title, String detail) {
        return write(exchange, status, type, title, detail, null);
    }

    public static Mono<Void> withRetryAfter(ServerWebExchange exchange, HttpStatus status,
                                           URI type, String title, String detail,
                                           Duration retryAfter) {
        return write(exchange, status, type, title, detail, retryAfter);
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                       URI type, String title, String detail,
                                       Duration retryAfter) {
        ServerHttpResponse res = exchange.getResponse();

        // If the response is already committed, someone upstream already
        // answered. Insisting throws UnsupportedOperationException when
        // touching headers that are now read-only, and that error hides in the
        // log the one that DOES matter: the one explaining why the request was
        // rejected.
        if (res.isCommitted()) {
            return Mono.empty();
        }

        res.setStatusCode(status);
        res.getHeaders().setContentType(PROBLEM_JSON);
        if (retryAfter != null) {
            res.getHeaders().add(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.toString());
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", resolveInstance(exchange));

        String requestId = exchange.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }

        // Extra keys put by whoever cuts the request. AccountStateGuard adds
        // `accountStatus`, which is what the frontend uses to decide which
        // screen to send it to. Same criterion as users-service's ProblemDetail.
        Object extras = exchange.getAttribute(ATTR_EXTRAS);
        if (extras instanceof Map<?, ?> m) {
            m.forEach((k, v) -> body.put(String.valueOf(k), v));
        }

        try {
            DataBuffer buffer = res.bufferFactory().wrap(MAPPER.writeValueAsBytes(body));
            return res.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            // If the error cannot even be serialized, close with the status
            // already set: propagating an exception from error handling would
            // be worse, and would return an opaque 500.
            return res.setComplete();
        }
    }

    /**
     * The path the client asked for, not the one the Gateway is serving right
     * now.
     *
     * <p>It matters in a single case, but it is the most looked-at one: when
     * the breaker opens, the request is forwarded to {@code /fallback/servicio}
     * and from there on {@code exchange.getRequest().getPath()} returns THAT.
     * The client used to receive {@code "instance": "/fallback/servicio"} — a
     * path it never called and that does not exist in the API — exactly at the
     * error most debugged across teams.
     */
    private static String resolveInstance(ServerWebExchange exchange) {
        Object original = exchange.getAttribute(
                ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (original instanceof Collection<?> urls) {
            for (Object u : urls) {
                if (u instanceof URI uri && uri.getRawPath() != null) {
                    return uri.getRawPath();
                }
            }
        }
        return exchange.getRequest().getPath().value();
    }

    private ProblemDetails() {
    }
}
