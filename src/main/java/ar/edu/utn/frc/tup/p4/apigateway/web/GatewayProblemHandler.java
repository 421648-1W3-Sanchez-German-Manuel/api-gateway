package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Maps a {@link ResponseStatusException} to the RFC 9457 body that
 * {@link ProblemDetails} already writes, with FIXED messages.
 *
 * <p>The exception's message is NEVER copied to the body: it can contain
 * internal paths, bean names or request fragments. What helps the caller is
 * the {@code type} (the handling branch) and a readable detail, not the
 * framework's dump.
 */
public final class GatewayProblemHandler {

    private GatewayProblemHandler() {
    }

    public static Mono<Void> handle(ServerWebExchange exchange, ResponseStatusException rse) {
        return switch (rse.getStatusCode().value()) {
            case 404 -> ProblemDetails.write(exchange, HttpStatus.NOT_FOUND,
                    ErrorTypes.ROUTE_NOT_FOUND, "Route not found",
                    "The requested route does not exist.");
            case 405 -> ProblemDetails.write(exchange, HttpStatus.METHOD_NOT_ALLOWED,
                    ErrorTypes.METHOD_NOT_ALLOWED, "Method not allowed",
                    "The HTTP method is not allowed for this route.");
            case 403 -> ProblemDetails.write(exchange, HttpStatus.FORBIDDEN,
                    ErrorTypes.ACCESS_DENIED, "Access denied",
                    "You do not have permission to access this resource.");
            default -> generic(exchange, rse.getStatusCode());
        };
    }

    private static Mono<Void> generic(ServerWebExchange exchange, HttpStatusCode code) {
        HttpStatus status = code instanceof HttpStatus h ? h : HttpStatus.INTERNAL_SERVER_ERROR;
        return ProblemDetails.write(exchange, status,
                ErrorTypes.UNEXPECTED_ERROR, status.getReasonPhrase(),
                "The request could not be processed.");
    }
}
