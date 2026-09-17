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
 * Step 1 of the pipeline - extends where the raw JWT comes from, without
 * touching the rest of SecurityConfig's contract. Spec "Sesion en Cookies"
 * S02.2/S02.3.
 *
 * Precedence order, and why it matters: the header is ALWAYS tried first.
 * Service tokens (client_credentials, MS role) never have a browser in the
 * middle and ALWAYS go by header - with this precedence they do not even look
 * at the cookie. The fu_at cookie is a fallback, not a second channel on equal
 * footing.
 *
 * Everything that runs after authentication (SessionGuard, PrivateRouteGuard,
 * AccountStateGuard, ServiceAudienceFilter, IdentityPropagationFilter) reads
 * the already-decoded Jwt from the SecurityContext or from the ATTR_JWT
 * attribute - none of them looks at the raw transport again. That is why this
 * change stays ENTIRELY encapsulated in Step 1 (verified against
 * SPEC-api-gateway.md S9 before writing this).
 */
@Component
public class CookieOrHeaderBearerConverter implements ServerAuthenticationConverter {

    public static final String ACCESS_COOKIE = "fu_at";

    /** Marks, for whoever needs it later, through which channel the raw token arrived. */
    public static final String ATTR_CHANNEL = "gateway.auth.channel";

    public enum Channel { HEADER, COOKIE }

    private final ServerBearerTokenAuthenticationConverter headerConverter =
            new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return headerConverter.convert(exchange)
                .doOnNext(auth -> exchange.getAttributes().put(ATTR_CHANNEL, Channel.HEADER))
                .switchIfEmpty(Mono.defer(() -> fromCookie(exchange)));
    }

    private Mono<Authentication> fromCookie(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(ACCESS_COOKIE);
        if (cookie == null || cookie.getValue().isBlank()) {
            return Mono.empty();
        }
        exchange.getAttributes().put(ATTR_CHANNEL, Channel.COOKIE);
        return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
    }
}
