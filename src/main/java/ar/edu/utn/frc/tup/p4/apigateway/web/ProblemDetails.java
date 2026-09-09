package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * El UNICO camino por el que el Gateway corta un request. Un filtro que
 * escribe un body a mano rompe la uniformidad del contrato y obliga al
 * frontend a tener una rama de manejo por filtro.
 *
 * <p>El cuerpo cumple RFC 9457 ({@code application/problem+json}) con los
 * campos {@code type, title, status, detail, instance} y, cuando viene bien,
 * el {@code requestId} para que el usuario pueda reportar el error y un mapa
 * de extras que el filtro que corta el request haya dejado en el exchange.
 */
public final class ProblemDetails {

    /** Exchange attribute donde un filtro deja claves extra para el cuerpo. */
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
        res.setStatusCode(status);
        res.getHeaders().setContentType(PROBLEM_JSON);
        if (retryAfter != null) {
            // DEC-01: el cliente tiene que saber que reintentar SIRVE aca, a
            // diferencia de un 401 donde reintentar no arregla nada.
            res.getHeaders().add(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
        }

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("type", type.toString());
        cuerpo.put("title", title);
        cuerpo.put("status", status.value());
        cuerpo.put("detail", detail);
        cuerpo.put("instance", exchange.getRequest().getPath().value());
        String requestId = exchange.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID);
        if (requestId != null) {
            cuerpo.put("requestId", requestId);
        }

        // Extras dejados por quien corto el request - AccountStateGuard agrega
        // accountStatus, que es lo que el frontend usa para decidir que pantalla
        // mostrar. Mismo criterio que el ProblemDetail de users-service.
        Object extras = exchange.getAttribute(ATTR_EXTRAS);
        if (extras instanceof Map<?, ?> m) {
            m.forEach((k, v) -> cuerpo.put(String.valueOf(k), v));
        }

        try {
            DataBuffer buffer = res.bufferFactory().wrap(MAPPER.writeValueAsBytes(cuerpo));
            return res.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return res.setComplete();
        }
    }

    private ProblemDetails() { }
}