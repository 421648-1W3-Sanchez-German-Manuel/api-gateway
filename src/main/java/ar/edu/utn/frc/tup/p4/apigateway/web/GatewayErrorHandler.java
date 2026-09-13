package ar.edu.utn.frc.tup.p4.apigateway.web;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Todo {@code ResponseStatusException} que escapa el pipeline (ruta fuera de
 * la allowlist, metodo no permitido, ...) sale en RFC 9457 via
 * {@link GatewayProblemHandler}, no en el formato por defecto de WebFlux
 * ({@code timestamp, path, status, error} sin {@code type}).
 *
 * <p>Solo intercepta {@code ResponseStatusException}: cualquier otro error
 * pasa al handler habitual — en particular el 503 del circuit breaker, que
 * tiene su propio camino via {@link FallbackController}.
 * {@code @Order(-2)} lo ubica antes de
 * {@code DefaultErrorWebExceptionHandler}, que esta en -1.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!(ex instanceof ResponseStatusException rse)) {
            return Mono.error(ex);
        }
        return GatewayProblemHandler.handle(exchange, rse);
    }
}
