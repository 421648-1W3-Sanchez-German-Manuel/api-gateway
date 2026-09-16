package ar.edu.utn.frc.tup.p4.apigateway.security;

import org.springframework.http.HttpCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Paso 1 del pipeline - extiende de donde sale el JWT crudo, sin tocar el
 * resto del contrato de SecurityConfig. Spec "Sesion en Cookies" S02.2/S02.3.
 *
 * Orden de precedencia, y por que importa: el header SIEMPRE se prueba
 * primero. Los tokens de servicio (client_credentials, rol MS) nunca tienen
 * navegador de por medio y SIEMPRE van por header - con esta precedencia ni
 * siquiera llegan a mirar la cookie. La cookie fu_at es un fallback, no un
 * segundo canal en pie de igualdad.
 *
 * Todo lo que corre despues de la autenticacion (SessionGuard,
 * PrivateRouteGuard, AccountStateGuard, ServiceAudienceFilter,
 * IdentityPropagationFilter) lee el Jwt ya decodificado del SecurityContext o
 * del atributo ATTR_JWT - ninguno vuelve a mirar el transporte crudo. Por eso
 * este cambio queda encapsulado ENTERAMENTE en el Paso 1 (verificado contra
 * SPEC-api-gateway.md S9 antes de escribir esto).
 */
@Component
public class CookieOrHeaderBearerConverter implements ServerAuthenticationConverter {

    public static final String ACCESS_COOKIE = "fu_at";

    /** Marca, para quien lo necesite mas adelante, por que canal llego el crudo. */
    public static final String ATTR_CANAL = "gateway.auth.canal";

    public enum Canal { HEADER, COOKIE }

    private final ServerBearerTokenAuthenticationConverter headerConverter =
            new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return headerConverter.convert(exchange)
                .doOnNext(auth -> exchange.getAttributes().put(ATTR_CANAL, Canal.HEADER))
                .switchIfEmpty(Mono.defer(() -> desdeCookie(exchange)));
    }

    private Mono<Authentication> desdeCookie(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(ACCESS_COOKIE);
        if (cookie == null || cookie.getValue().isBlank()) {
            return Mono.empty();
        }
        exchange.getAttributes().put(ATTR_CANAL, Canal.COOKIE);
        return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
    }
}
