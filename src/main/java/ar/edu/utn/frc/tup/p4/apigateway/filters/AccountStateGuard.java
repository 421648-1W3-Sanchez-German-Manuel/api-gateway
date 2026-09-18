package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.AccountGateProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import ar.edu.utn.frc.tup.p4.apigateway.security.PrincipalContext;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * Pipeline step 6 - @Order(5) - DEC-23 - NOT in the manifest.
 *
 * The COARSE account-status gate. The whole rule, in one line:
 *
 *   if the principal is a person and the account is not enabled, only the
 *   exempt prefixes and /api/{x}/public/** are allowed.
 *
 * The exempt prefixes live in {@code gateway.account-gate.exempt-prefixes}
 * (default: users-service): the gateway needs to know nobody's routes, and
 * onboarding a new micro is config, not a PR here.
 *
 * This does NOT violate R3: R3 says the gateway does not authorize by ROLE, and
 * it does not - it never reads `roles` nor compares role against route. An
 * account's status is not a role: it is "who you are" vs "whether you can act".
 */
@Component
public class AccountStateGuard implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccountStateGuard.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final AccountGateProperties gate;

    public AccountStateGuard(AccountGateProperties gate) {
        this.gate = gate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (Boolean.TRUE.equals(exchange.getAttribute(PublicRouteGuard.ATTR_IS_PUBLIC))) {
            return chain.filter(exchange);
        }
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);
        if (jwt == null || !PrincipalType.USER.matches(jwt.getClaimAsString("type"))) {
            return chain.filter(exchange);   // service: no status to look at
        }

        String est = jwt.getClaimAsString("est");
        Boolean pwd = PrincipalContext.booleanClaim(jwt, "pwd");
        Boolean onb = PrincipalContext.booleanClaim(jwt, "onb");

        // DEC-44: all three are MANDATORY in a person token. Missing means a
        // users-service that does not emit them yet: it is rejected, and the
        // log names which one is missing.
        if (est == null || pwd == null || onb == null) {
            log.warn("JWT_REJECTED reason=claim-missing claim={} sub={}",
                    est == null ? "est" : pwd == null ? "pwd" : "onb", jwt.getSubject());
            return ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                    ErrorTypes.NOT_AUTHENTICATED, "Not authenticated",
                    "The token is not valid for this route.");
        }

        boolean enabled = "ACTIVE".equals(est) && !pwd && !onb;
        if (enabled) {
            return chain.filter(exchange);
        }

        // Not enabled: it can only talk to the exempt prefixes.
        String path = exchange.getRequest().getPath().value();
        boolean exempt = gate.exemptPrefixes().stream()
                .anyMatch(prefix -> MATCHER.match(prefix, path));
        if (exempt) {
            return chain.filter(exchange);
        }

        // The SAME types users-service returns from its fine gates, so the
        // frontend has a single handling branch.
        if (!"ACTIVE".equals(est)) {
            return reject(exchange, ErrorTypes.PENDING_ACCOUNT, "Account pending validation",
                    "The account is not active.", Map.of("accountStatus", est));
        }
        if (pwd) {
            return reject(exchange, ErrorTypes.PASSWORD_CHANGE_REQUIRED,
                    "Password change required",
                    "You must change your password before continuing.", Map.of());
        }
        return reject(exchange, ErrorTypes.ONBOARDING_PENDING, "Onboarding pending",
                "Complete the onboarding before continuing.", Map.of());
    }

    private Mono<Void> reject(ServerWebExchange exchange, URI type, String title,
                              String detail, Map<String, Object> extras) {
        exchange.getAttributes().put(ProblemDetails.ATTR_EXTRAS, extras);
        return ProblemDetails.write(exchange, HttpStatus.FORBIDDEN, type, title, detail);
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
