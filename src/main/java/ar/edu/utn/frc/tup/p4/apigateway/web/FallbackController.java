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

    private final ResilienceProperties resiliencia;

    public FallbackController(ResilienceProperties resiliencia) {
        this.resiliencia = resiliencia;
    }

    /**
     * Aparece SIETE veces en el spec, una por verbo, y esta bien asi: el
     * {@code @RequestMapping} no declara {@code method} porque el forward del
     * breaker conserva el metodo original, asi que cualquier verbo puede caer
     * aca. Agregarle {@code method = GET} para "limpiar la documentacion"
     * romperia el fallback de todo POST, PATCH y DELETE — la doc se veria mejor
     * y el breaker dejaria de contestar en la mitad de los casos.
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
            // Invocacion DIRECTA, no el forward del breaker: el forward corre
            // sobre el exchange ya ruteado y conserva GATEWAY_ROUTE_ATTR. Sin
            // ruta resuelta no hay destino caido que reportar — contestar 503
            // aca permite enumerar servicios y ensucia el monitoreo con caidas
            // falsas. Es un 404 comun, con el type comun.
            // El serviceId del path NO se copia al cuerpo: es input crudo.
            return ProblemDetails.write(exchange, HttpStatus.NOT_FOUND,
                    ErrorTypes.ROUTE_NOT_FOUND, "Ruta inexistente",
                    "La ruta solicitada no existe.");
        }
        String destino = destinoReal(exchange, serviceId);
        return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorTypes.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "El servicio '" + destino + "' no esta respondiendo. Reintente en unos segundos.",
                resiliencia.fallbackRetryAfter());
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
