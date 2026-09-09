package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Destino del circuit breaker: 503 con la MISMA forma que el resto de los
 * errores. Si este controller respondiera otra cosa, el cliente tendria una
 * rama de manejo por filtro: imposible de mantener.
 *
 * <p>El path {@code /fallback/{serviceId}} se configura en cada ruta
 * (gestion de L10) con el filtro {@code RequestCircuitBreaker} de Resilience4j.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{serviceId}")
    public Mono<Void> fallback(@PathVariable String serviceId, ServerWebExchange exchange) {
        return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorTypes.SERVICE_UNAVAILABLE, "Service unavailable",
                "Service '" + serviceId + "' is not responding. Try again in a few seconds.",
                Duration.ofSeconds(10));
    }
}