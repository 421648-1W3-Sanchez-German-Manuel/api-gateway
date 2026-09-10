package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.security.SessionValidator;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Verifica la sesion unica DESPUES de que Security valido firma, exp e iss, y
 * ANTES de que el request se rutee.
 *
 * Es un WebFilter y no un OAuth2TokenValidator por el motivo que explica
 * `SessionValidator`: la interfaz del validator es sincronica y leer Redis no.
 */
@Component
public class SessionGuard implements WebFilter, Ordered {

    private final SessionValidator validador;

    public SessionGuard(SessionValidator validador) { this.validador = validador; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast)
                .flatMap(validador::verificar)
                // El defaultIfEmpty va ACA, sobre el Resultado, y NO como un
                // switchIfEmpty al final de la cadena. Los metodos de
                // ProblemDetails devuelven Mono<Void>, que SIEMPRE completa
                // vacio: un switchIfEmpty despues de ellos se dispara aunque ya
                // se haya escrito el 401, y el request rechazado sigue viaje al
                // destino. El cliente ve 401 y el backend recibe el request
                // igual. Es el mismo error que en PrivateRouteGuard.
                //
                // Sin Authentication -- ruta publica -- el guard no aplica.
                .defaultIfEmpty(SessionValidator.Resultado.VIGENTE)
                .flatMap(resultado -> switch (resultado) {
                    case VIGENTE -> chain.filter(exchange);
                    case SUPERADA -> ProblemDetails.write(exchange,
                            HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_SUPERSEDED,
                            "Session superseded",
                            "Another device signed in with this account.");
                    case CERRADA -> ProblemDetails.write(exchange,
                            HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_CLOSED,
                            "Session closed",
                            "The session is no longer active. Sign in again.");
                    // DEC-01: fail-closed, pero 503 y no 401. Reintentar SI
                    // sirve aca, a diferencia de una sesion cerrada.
                    case NO_VERIFICABLE -> ProblemDetails.withRetryAfter(exchange,
                            HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.SERVICE_UNAVAILABLE,
                            "Could not verify the session",
                            "Try again in a few seconds.", Duration.ofSeconds(5));
                });
    }

    /**
     * Despues de la cadena de autenticacion: necesita el Jwt ya validado en el
     * SecurityContext. La cadena de Security corre en -100
     * (SecurityWebFiltersOrder), asi que cualquier valor mayor sirve; 0 deja
     * margen por si hace falta intercalar algo antes.
     *
     * Ojo: este es el orden de los WebFilter, que es OTRO orden que el de los
     * GlobalFilter del pipeline de ruteo (@Order(1) a @Order(8)).
     */
    @Override
    public int getOrder() { return 0; }
}
