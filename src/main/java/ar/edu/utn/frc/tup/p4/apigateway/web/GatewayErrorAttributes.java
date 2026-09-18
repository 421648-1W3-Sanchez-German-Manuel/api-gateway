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
 * Bridge between the error attributes Spring Boot accumulates and the
 * RFC 9457 body that {@link ProblemDetails} already writes. It keeps the
 * requestId traceable inside the problem+json body without each filter having
 * to remember to copy it.
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
