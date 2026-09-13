package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Mapea un {@link ResponseStatusException} al cuerpo RFC 9457 que
 * {@link ProblemDetails} ya escribe, con mensajes FIJOS.
 *
 * <p>El mensaje de la excepcion NUNCA se copia al cuerpo: puede contener paths
 * internos, nombres de beans o fragmentos del request. Lo que ayuda a quien
 * llama es el {@code type} (rama de manejo) y un detalle legible, no el
 * volcado del framework.
 */
public final class GatewayProblemHandler {

    private GatewayProblemHandler() {
    }

    public static Mono<Void> handle(ServerWebExchange exchange, ResponseStatusException rse) {
        return switch (rse.getStatusCode().value()) {
            case 404 -> ProblemDetails.write(exchange, HttpStatus.NOT_FOUND,
                    ErrorTypes.ROUTE_NOT_FOUND, "Ruta inexistente",
                    "La ruta solicitada no existe.");
            case 405 -> ProblemDetails.write(exchange, HttpStatus.METHOD_NOT_ALLOWED,
                    ErrorTypes.METHOD_NOT_ALLOWED, "Metodo no permitido",
                    "El metodo HTTP no esta permitido para esta ruta.");
            case 403 -> ProblemDetails.write(exchange, HttpStatus.FORBIDDEN,
                    ErrorTypes.ACCESS_DENIED, "Acceso denegado",
                    "No tiene permiso para acceder a este recurso.");
            default -> generico(exchange, rse.getStatusCode());
        };
    }

    private static Mono<Void> generico(ServerWebExchange exchange, HttpStatusCode codigo) {
        HttpStatus status = codigo instanceof HttpStatus h ? h : HttpStatus.INTERNAL_SERVER_ERROR;
        return ProblemDetails.write(exchange, status,
                ErrorTypes.UNEXPECTED_ERROR, status.getReasonPhrase(),
                "La solicitud no pudo procesarse.");
    }
}
