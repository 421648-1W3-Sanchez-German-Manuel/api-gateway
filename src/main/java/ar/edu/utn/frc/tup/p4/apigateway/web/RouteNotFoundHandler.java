package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Un prefijo que no esta en la allowlist cae, sin esto, en el handler de
 * errores por defecto de WebFlux, que responde {@code {timestamp, path,
 * status, error}} - un 404 correcto pero SIN {@code type}. El contrato dice
 * que todo error es RFC 9457 y que el cliente ramifica por {@code type}.
 *
 * <p>Solo intercepta el 404: cualquier otro error pasa al handler habitual
 * - en particular el 503 del circuit breaker, que tiene su propio camino via
 * {@link FallbackController}. {@code @Order(-2)} lo ubica antes de
 * {@code DefaultErrorWebExceptionHandler}, que esta en -1.
 */
@Component
@Order(-2)
public class RouteNotFoundHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!(ex instanceof ResponseStatusException rse)
                || rse.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
            return Mono.error(ex);
        }
        return ProblemDetails.write(exchange, HttpStatus.NOT_FOUND,
                ErrorTypes.ROUTE_NOT_FOUND, "Ruta inexistente",
                "La ruta solicitada no existe.");
    }
}