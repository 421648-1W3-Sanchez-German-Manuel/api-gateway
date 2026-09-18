package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.routing.PublicRouteMatcher;
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
 * Verifies the single session AFTER Security validated signature, exp and iss,
 * and BEFORE the request is routed.
 *
 * It is a WebFilter and not an OAuth2TokenValidator for the reason explained in
 * {@code SessionValidator}: the validator interface is synchronous and reading
 * Redis is not.
 */
@Component
public class SessionGuard implements WebFilter, Ordered {

    private final SessionValidator validator;
    private final PublicRouteMatcher publicRoutes;

    public SessionGuard(SessionValidator validator, PublicRouteMatcher publicRoutes) {
        this.validator = validator;
        this.publicRoutes = publicRoutes;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Same as PrivateRouteGuard/AccountStateGuard: a public route does not
        // need a current session, it is the one that CREATES or ROTATES it
        // (login, 2fa, refresh). Without this skip, a stale fu_at cookie -from
        // an already superseded session- that the browser attaches only because
        // it shares the origin (not by the frontend's choice, as used to happen
        // with the header) rejects the NEW login attempt because of the OLD
        // session. Before cookies this never fired: the interceptor never sent
        // the Authorization header to a public route.
        //
        // It asks PublicRouteMatcher DIRECTLY and does not read
        // PublicRouteGuard.ATTR_IS_PUBLIC: that attribute is written by a
        // GlobalFilter, and this is a WebFilter. The whole WebFilter phase runs
        // before the first GlobalFilter, so the attribute was ALWAYS null here
        // and this skip never fired -- the exact bug the paragraph above says it
        // prevents. Both paths use the same matcher, so "public" keeps meaning
        // one single thing.
        if (publicRoutes.isPublic(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast)
                .flatMap(validator::verify)
                // The defaultIfEmpty goes HERE, on the Decision, and NOT as a
                // switchIfEmpty at the end of the chain. ProblemDetails methods
                // return Mono<Void>, which ALWAYS completes empty: a
                // switchIfEmpty after them fires even when the 401 was already
                // written, and the rejected request keeps travelling to its
                // destination. The client sees 401 and the backend receives the
                // request anyway. It is the same bug as in PrivateRouteGuard.
                //
                // No Authentication -- public route -- the guard does not apply.
                .defaultIfEmpty(SessionValidator.Decision.VALID)
                .flatMap(decision -> switch (decision) {
                    case VALID -> chain.filter(exchange);
                    case SUPERSEDED -> ProblemDetails.write(exchange,
                            HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_SUPERSEDED,
                            "Session superseded",
                            "Another device signed in with this account.");
                    case CLOSED -> ProblemDetails.write(exchange,
                            HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_CLOSED,
                            "Session closed",
                            "The session is no longer active. Sign in again.");
                    // DEC-01: fail-closed, but 503 and not 401. Retrying DOES
                    // help here, unlike an closed session.
                    case UNVERIFIABLE -> ProblemDetails.withRetryAfter(exchange,
                            HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.SERVICE_UNAVAILABLE,
                            "Could not verify the session",
                            "Try again in a few seconds.", Duration.ofSeconds(5));
                });
    }

    /**
     * After the authentication chain: it needs the already-validated Jwt in the
     * SecurityContext. Security's chain runs at -100
     * (SecurityWebFiltersOrder), so any higher value works; 0 leaves room if
     * something needs to be interleaved before it.
     *
     * Note: this is the WebFilter ordering, which is a DIFFERENT ordering from
     * the GlobalFilter pipeline of routing (@Order(1) to @Order(8)).
     */
    @Override
    public int getOrder() { return 0; }
}
