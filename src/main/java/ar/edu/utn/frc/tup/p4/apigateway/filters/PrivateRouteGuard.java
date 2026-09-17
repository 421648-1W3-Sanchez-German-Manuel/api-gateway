package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
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
import java.util.Optional;

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
        if (Boolean.TRUE.equals(exchange.getAttribute(PublicRouteGuard.ATTR_IS_PUBLIC))) {
            return chain.filter(exchange);
        }

        // The absent/present decision is resolved into an Optional BEFORE the
        // chain runs. Deciding it with switchIfEmpty on the downstream would
        // be wrong: chain.filter() returns Mono<Void>, which ALSO completes
        // empty, so every valid request would be rejected with a 401.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybe -> {
                    if (maybe.isEmpty()) {
                        return reject(exchange);
                    }
                    Jwt jwt = maybe.get();
                    String reason = checkShape(jwt, exchange);
                    if (reason != null) {
                        log.warn("JWT_REJECTED reason={} sub={}", reason, jwt.getSubject());
                        return reject(exchange);
                    }
                    exchange.getAttributes().put(ATTR_JWT, jwt);
                    return chain.filter(exchange);
                });
    }

    /**
     * Returns the reason, or null when the token is well formed.
     *
     * Spec "Sesion en Cookies" decision 3 - post-cutover state: a person token
     * (type=user) is only valid if it arrived through the fu_at cookie; a
     * service token (type=service) only if it arrived through the Authorization
     * header. It closes the channel by claim type, not by habit - a stolen
     * person token pasted by hand into a header stops working, which is the
     * whole point of moving to HttpOnly cookies.
     */
    private String checkShape(Jwt jwt, ServerWebExchange exchange) {
        String type = jwt.getClaimAsString("type");
        if (type == null) {
            return "claim-missing-type";
        }

        PrincipalType principalType;
        try {
            principalType = PrincipalType.from(type);
        } catch (IllegalArgumentException e) {
            return "type-unknown";
        }

        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return "claim-missing-sub";
        }

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            return "claim-missing-roles";
        }

        // A service token WITHOUT the MS role is not a service token.
        if (principalType == PrincipalType.SERVICE && !roles.contains("MS")) {
            return "service-without-MS";
        }

        CookieOrHeaderBearerConverter.Channel channel =
                exchange.getAttribute(CookieOrHeaderBearerConverter.ATTR_CHANNEL);
        if (principalType == PrincipalType.USER
                && channel != CookieOrHeaderBearerConverter.Channel.COOKIE) {
            return "person-via-header";
        }
        if (principalType == PrincipalType.SERVICE
                && channel != CookieOrHeaderBearerConverter.Channel.HEADER) {
            return "service-via-cookie";
        }

        return null;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        return ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                ErrorTypes.NOT_AUTHENTICATED, "Not authenticated",
                "The token is not valid for this route.");
    }

    @Override
    public int getOrder() {
        return 40;
    }
}
