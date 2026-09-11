package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;

/**
 * Destino del circuit breaker: 503 con la MISMA forma que el resto de los
 * errores. Si este controller respondiera otra cosa, el cliente tendria una
 * rama de manejo por filtro: imposible de mantener.
 *
 * <p>El path {@code /fallback/{serviceId}} sale del {@code fallbackUri} del
 * filtro CircuitBreaker, que es un literal compartido por todas las rutas
 * ({@code forward:/fallback/servicio}). O sea que el {@code serviceId} del path
 * NO es el destino: es la palabra "servicio". El destino real se saca de la
 * ruta resuelta, que sigue en los atributos del exchange despues del forward.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{serviceId}")
    public Mono<Void> fallback(@PathVariable String serviceId, ServerWebExchange exchange) {
        String destino = destinoReal(exchange, serviceId);
        return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorTypes.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "El servicio '" + destino + "' no esta respondiendo. Reintente en unos segundos.",
                Duration.ofSeconds(10));
    }

    /**
     * El serviceId al que se iba a rutear. Nombrarlo importa: es el 503 que ve
     * un equipo cuando su micro esta en la allowlist pero no tiene instancias
     * arriba, y decirle "el servicio 'servicio' no responde" lo manda a buscar
     * un servicio que no existe.
     */
    private String destinoReal(ServerWebExchange exchange, String fallbackDelPath) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null && route.getUri().getHost() != null) {
            return route.getUri().getHost().toLowerCase(Locale.ROOT);
        }
        return fallbackDelPath;
    }
}
