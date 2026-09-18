package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.config.properties.ResilienceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * Circuit breaker destination: 503 with the SAME shape as the rest of the
 * errors. If this controller answered something else, the client would have
 * one handling branch per filter: impossible to maintain.
 *
 * <p>The {@code /fallback/{serviceId}} path comes from the CircuitBreaker
 * filter's {@code fallbackUri}, a literal shared by all routes
 * ({@code forward:/fallback/servicio}). That is, the {@code serviceId} in the
 * path is NOT the destination: it is the word "servicio". The real destination
 * is taken from the resolved route, which stays in the exchange attributes
 * after the forward.
 */
@RestController
public class FallbackController {

    private final ResilienceProperties resilience;

    public FallbackController(ResilienceProperties resilience) {
        this.resilience = resilience;
    }

    /**
     * It appears SEVEN times in the spec, one per verb, and that is fine:
     * {@code @RequestMapping} does not declare {@code method} because the
     * breaker's forward preserves the original method, so any verb can land
     * here. Adding {@code method = GET} to "clean up the documentation" would
     * break the fallback of every POST, PATCH and DELETE — the docs would look
     * better and the breaker would stop answering in half the cases.
     */
    @Operation(summary = "INTERNO - destino del circuit breaker",
               description = """
                       **No se llama directo.** Es a donde el filtro CircuitBreaker hace
                       `forward:` cuando el destino real esta caido, y su respuesta es el 503
                       que termina viendo el cliente que pidio OTRA ruta.

                       Invocarlo a mano devuelve **404** `route-not-found`, no 503, y es a
                       proposito: sin una ruta resuelta en el exchange no hay destino caido que
                       reportar. Contestar 503 permitiria enumerar servicios y ensuciaria el
                       monitoreo con caidas falsas.

                       Aparece una vez por verbo porque el forward conserva el metodo original
                       del request.""")
    @ApiResponse(responseCode = "503", description = """
            `type`: `service-unavailable`. El destino no responde. Trae `Retry-After`, y
            nombra al servicio REAL -no la palabra "servicio" del path-, porque decirle a un
            equipo que "el servicio 'servicio' no responde" lo manda a buscar algo que no
            existe.""")
    @ApiResponse(responseCode = "404", description = """
            `type`: `route-not-found`. Llamada directa, sin ruta resuelta. El `serviceId` del
            path NO se copia al cuerpo: es input crudo.""")
    @RequestMapping("/fallback/{serviceId}")
    public Mono<Void> fallback(@PathVariable String serviceId, ServerWebExchange exchange) {
        if (exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) == null) {
            // DIRECT invocation, not the breaker's forward: the forward runs
            // over the already-routed exchange and keeps GATEWAY_ROUTE_ATTR.
            // With no resolved route there is no down destination to report —
            // answering 503 here would allow enumerating services and would
            // pollute monitoring with false outages. It is a plain 404, with
            // the common type.
            // The serviceId in the path is NOT copied to the body: it is raw
            // input.
            return ProblemDetails.write(exchange, HttpStatus.NOT_FOUND,
                    ErrorTypes.ROUTE_NOT_FOUND, "Route not found",
                    "The requested route does not exist.");
        }
        String destination = realDestination(exchange, serviceId);
        return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorTypes.SERVICE_UNAVAILABLE, "Service unavailable",
                "The service '" + destination + "' is not responding. Retry in a few seconds.",
                resilience.fallbackRetryAfter());
    }

    /**
     * The serviceId it was going to be routed to. Naming it matters: it is the
     * 503 a team sees when its micro is on the allowlist but has no instances
     * up, and telling them "the service 'servicio' is not responding" sends
     * them looking for a service that does not exist.
     */
    private String realDestination(ServerWebExchange exchange, String fallbackPathVariable) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null && route.getUri().getHost() != null) {
            return route.getUri().getHost().toLowerCase(Locale.ROOT);
        }
        return fallbackPathVariable;
    }
}
