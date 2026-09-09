package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Pipeline step 5 - @Order(4).
 *
 * Defence in depth. It checks the SHAPE of the token, not the permission:
 * this is NOT authorizing by role (R3). There is no comparison against ADMIN,
 * PROFESSOR or STUDENT in this class, and there must never be one.
 */
@Component
public class PrivateRouteGuard implements GlobalFilter, Ordered {

    public static final String ATTR_JWT = "gateway.jwt";
    private static final Logger log = LoggerFactory.getLogger(PrivateRouteGuard.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (Boolean.TRUE.equals(exchange.getAttribute(PublicRouteGuard.ATTR_ES_PUBLICA))) {
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .filter(a -> a.getPrincipal() instanceof Jwt)
                .map(a -> (Jwt) a.getPrincipal())
                .flatMap(jwt -> {
                    String reason = coherencia(jwt);
                    if (reason != null) {
                        log.warn("JWT_RECHAZADO reason={} sub={}", reason, jwt.getSubject());
                        return reject(exchange);
                    }
                    exchange.getAttributes().put(ATTR_JWT, jwt);
                    return chain.filter(exchange);
                })
                .switchIfEmpty(Mono.defer(() -> reject(exchange)));
    }

    /** Returns the reason, or null when the token is well formed. */
    private String coherencia(Jwt jwt) {
        String type = jwt.getClaimAsString("type");
        if (type == null) {
            return "claim-ausente-type";
        }

        PrincipalType tipo;
        try {
            tipo = PrincipalType.from(type);
        } catch (IllegalArgumentException e) {
            return "type-desconocido";
        }

        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "claim-ausente-sub";
        }

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            return "claim-ausente-roles";
        }

        // A service token WITHOUT the MS role is not a service token.
        if (tipo == PrincipalType.SERVICE && !roles.contains("MS")) {
            return "servicio-sin-MS";
        }

        return null;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        return ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                ErrorTypes.NOT_AUTHENTICATED, "No autenticado",
                "El token no es valido para esta ruta.");
    }

    @Override
    public int getOrder() {
        return 40;
    }
}
