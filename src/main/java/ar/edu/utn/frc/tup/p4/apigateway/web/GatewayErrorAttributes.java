package ar.edu.utn.frc.tup.p4.apigateway.web;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puente entre los atributos de error que Spring Boot acumula y el cuerpo
 * RFC 9457 que {@link ProblemDetails} ya escribe. Mantiene la trazabilidad
 * del requestId dentro del cuerpo problem+json sin que cada filtro tenga
 * que recordar copiarlo.
 */
@Component
public class GatewayErrorAttributes implements ErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request,
                                                  ErrorAttributeOptions options) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        Throwable error = getError(request);
        if (error instanceof ResponseStatusException rse) {
            attrs.put("status", rse.getStatusCode().value());
        }
        return attrs;
    }

    @Override
    public Throwable getError(ServerRequest request) {
        return request.exchange().getAttribute("org.springframework.boot.web.reactive.error.error");
    }

    @Override
    public void storeErrorInformation(Throwable error, ServerWebExchange exchange) {
        exchange.getAttributes().put("org.springframework.boot.web.reactive.error.error", error);
    }
}