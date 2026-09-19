package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Mono;

/**
 * THE last link of the error contract: everything that escapes the pipeline
 * comes out as RFC 9457, like every other cut the Gateway makes.
 *
 * <p>A {@code ResponseStatusException} (route outside the allowlist, method not
 * allowed, ...) is mapped by {@link GatewayProblemHandler}. Anything else is an
 * UNEXPECTED error and comes out as a 500 with the same shape — never with
 * WebFlux's default format ({@code timestamp, path, status, error}, no
 * {@code type}), and never with an empty body.
 *
 * <p>It used to only intercept {@code ResponseStatusException} and re-throw the
 * rest, leaving them to Boot's handler. That path was BROKEN: the
 * {@code GatewayErrorAttributes} bean hardcoded Boot 3's attribute key, so
 * Boot's handler could not find the error and every unexpected exception came
 * out as a 500 with no body and no {@code Content-Type}. Handling it here
 * removes the dependency on that key altogether.
 *
 * <p>{@code @Order(-2)} places it before {@code DefaultErrorWebExceptionHandler},
 * which is at -1. The circuit breaker's 503 does NOT reach here: it has its own
 * path via {@link FallbackController}.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return GatewayProblemHandler.handle(exchange, rse);
        }

        // The client hung up: a browser navigating away mid-request, a dropped
        // mobile connection. Routine under load and not a gateway failure.
        // There is nobody left to answer, and reporting each one at ERROR with
        // a stack trace buries the failures that ARE unexpected -- the only
        // reason the log line below exists.
        //
        // The check is the framework's own (the same helper WebFlux uses for
        // its `DisconnectedClient` log category) rather than a local list of
        // exception types and messages, which differ per connector and per
        // platform and would silently stop matching.
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.debug("CLIENT_DISCONNECTED {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(), ex);
            return Mono.empty();
        }

        // Boot's handler is no longer reached, so nobody downstream will log
        // this. The stack trace of an unexpected 500 is the only evidence there
        // is of why it happened: it goes to the log, never to the body.
        log.error("UNEXPECTED_ERROR {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(), ex);

        return ProblemDetails.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorTypes.UNEXPECTED_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "The request could not be processed.");
    }
}
