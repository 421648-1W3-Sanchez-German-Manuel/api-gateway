package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * Pipeline step 6 - @Order(5) - DEC-23 - NOT in the manifest.
 *
 * The COARSE account-status gate. The whole rule, in one line:
 *
 *   if the principal is a person and the account is not enabled, only
 *   /api/users/** and /api/{x}/public/** are allowed.
 *
 * It composes cleanly because ALL the routes exempt from the three fine gates
 * belong to users-service: the gateway needs to know nobody's routes.
 *
 * This does NOT violate R3: R3 says the gateway does not authorize by ROLE, and
 * it does not - it never reads `roles` nor compares role against route. An
 * account's status is not a role: it is "who you are" vs "whether you can act".
 */
@Component
public class AccountStateGuard implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccountStateGuard.class);
    private static final String PREFIJO_USERS = "/api/users/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (Boolean.TRUE.equals(exchange.getAttribute(PublicRouteGuard.ATTR_ES_PUBLICA))) {
            return chain.filter(exchange);
        }
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);
        if (jwt == null || !"user".equals(jwt.getClaimAsString("type"))) {
            return chain.filter(exchange);   // service: no status to look at
        }

        String est = jwt.getClaimAsString("est");
        Boolean pwd = jwt.getClaim("pwd");
        Boolean onb = jwt.getClaim("onb");

        // DEC-44: all three are MANDATORY in a person token. Missing means a
        // users-service that does not emit them yet: it is rejected, and the
        // log names which one is missing.
        if (est == null || pwd == null || onb == null) {
            log.warn("JWT_RECHAZADO reason=claim-ausente claim={} sub={}",
                    est == null ? "est" : pwd == null ? "pwd" : "onb", jwt.getSubject());
            return ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                    ErrorTypes.NOT_AUTHENTICATED, "No autenticado",
                    "El token no es valido para esta ruta.");
        }

        boolean habilitada = "ACTIVE".equals(est) && !pwd && !onb;
        if (habilitada) {
            return chain.filter(exchange);
        }

        // Not enabled: it can only talk to users-service.
        if (exchange.getRequest().getPath().value().startsWith(PREFIJO_USERS)) {
            return chain.filter(exchange);
        }

        // The SAME types users-service returns from its fine gates, so the
        // frontend has a single handling branch.
        if (!"ACTIVE".equals(est)) {
            return reject(exchange, ErrorTypes.PENDING_ACCOUNT, "Cuenta pendiente de validacion",
                    "La cuenta no esta activa.", Map.of("accountStatus", est));
        }
        if (pwd) {
            return reject(exchange, ErrorTypes.PASSWORD_CHANGE_REQUIRED,
                    "Cambio de contrasena requerido",
                    "Debe cambiar su contrasena antes de continuar.", Map.of());
        }
        return reject(exchange, ErrorTypes.ONBOARDING_PENDING, "Onboarding pendiente",
                "Complete el onboarding antes de continuar.", Map.of());
    }

    private Mono<Void> reject(ServerWebExchange exchange, URI type, String titulo,
                              String detalle, Map<String, Object> extras) {
        exchange.getAttributes().put(ProblemDetails.ATTR_EXTRAS, extras);
        return ProblemDetails.write(exchange, HttpStatus.FORBIDDEN, type, titulo, detalle);
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
