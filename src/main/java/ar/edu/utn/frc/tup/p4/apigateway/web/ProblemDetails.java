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
 * escriba una respuesta a mano rompe la uniformidad del contrato de errores,
 * y el frontend termina con una rama de manejo por filtro.
 */
public final class ProblemDetails {

    /** Atributo del exchange donde un filtro deja claves extra para el body. */
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

        // Si la respuesta ya se comprometio, alguien mas arriba ya contesto.
        // Insistir tira UnsupportedOperationException al tocar headers que ya
        // son de solo lectura, y ese error tapa en el log al que SI importa:
        // el que explica por que se rechazo el request.
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
        body.put("instance", exchange.getRequest().getPath().value());

        String requestId = exchange.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }

        // Claves extra que pone quien corta el request. AccountStateGuard agrega
        // `accountStatus`, que es lo que el frontend usa para decidir a que
        // pantalla mandar. Mismo criterio que el ProblemDetail de users-service.
        Object extras = exchange.getAttribute(ATTR_EXTRAS);
        if (extras instanceof Map<?, ?> m) {
            m.forEach((k, v) -> body.put(String.valueOf(k), v));
        }

        try {
            DataBuffer buffer = res.bufferFactory().wrap(MAPPER.writeValueAsBytes(body));
            return res.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            // Si ni siquiera se puede serializar el error, cerrar con el status
            // ya seteado: peor seria propagar una excepcion desde el manejo de
            // errores y devolver un 500 opaco.
            return res.setComplete();
        }
    }

    private ProblemDetails() {
    }
}
