package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import ar.edu.utn.frc.tup.p4.apigateway.security.PrincipalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Pipeline step 8 - @Order(7).
 *
 * THE ORDER OF THE TWO OPERATIONS IS PART OF THE CONTRACT:
 *   1. FIRST strip the five reserved headers, wherever they came from.
 *      ALWAYS - public routes included, where there is no token to inject.
 *   2. THEN inject the set derived ONLY from the validated Jwt.
 *
 * A value is never copied from the incoming request into an identity header.
 * The only source is the Jwt. Inverting this, or skipping the strip on
 * public routes, and anyone is ADMIN by sending a header.
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdentityPropagationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);

        ServerWebExchange mutated = exchange.mutate().request(r -> {
            // STEP 1 - always strip. Anti-spoofing.
            r.headers(h -> IdentityHeaders.RESERVED.forEach(h::remove));

            // STEP 2 - inject only when there is a validated identity.
            if (jwt == null) {
                return;
            }

            PrincipalContext principal = PrincipalContext.from(jwt);
            r.header(IdentityHeaders.PRINCIPAL_TYPE, principal.type().claim());

            if (principal.type() == PrincipalType.USER) {
                r.header(IdentityHeaders.USER_ID, principal.subject());
                r.header(IdentityHeaders.USER_ROLES, principal.rolesHeader());
            } else {
                r.header(IdentityHeaders.SERVICE_ID, principal.subject());
                r.header(IdentityHeaders.SERVICE_SCOPES, principal.scopesHeader());
                // DEC-10 - on_behalf_of stays HERE, in the gateway's log.
                // No X-On-Behalf-Of header is created: users-service does not
                // parse the JWT (DEC-08), so this log is the ONLY record of it.
                if (principal.onBehalfOf() != null) {
                    log.info("ON_BEHALF_OF service={} actor={} path={}",
                            principal.subject(), principal.onBehalfOf(),
                            exchange.getRequest().getPath().value());
                }
            }
            // The original Authorization is NOT touched: forwarded as is (DEC-03).
            // Since the "Sesion en Cookies" cutover (decision 3), this only ever
            // has something to forward for a service token: a person token is
            // rejected unless it arrived via the fu_at cookie, which never
            // populates Authorization on the incoming request in the first place.
        }).build();

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return 70;
    }
}
