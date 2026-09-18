package ar.edu.utn.frc.tup.p4.apigateway.web;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Every {@code ResponseStatusException} that escapes the pipeline (route
 * outside the allowlist, method not allowed, ...) comes out as RFC 9457 via
 * {@link GatewayProblemHandler}, not in WebFlux's default format
 * ({@code timestamp, path, status, error} without {@code type}).
 *
 * <p>It only intercepts {@code ResponseStatusException}: every other error goes
 * to the usual handler — in particular the circuit breaker's 503, which has
 * its own path via {@link FallbackController}. {@code @Order(-2)} places it
 * before {@code DefaultErrorWebExceptionHandler}, which is at -1.
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
