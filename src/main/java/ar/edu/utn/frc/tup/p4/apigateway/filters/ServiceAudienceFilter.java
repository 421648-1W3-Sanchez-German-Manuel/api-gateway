package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Pipeline step 7 - @Order(6) - DEC-04 - NOT in the manifest.
 *
 * Why it does not live in Spring Security: the Security chain runs BEFORE the
 * route is resolved, so the destination is not known yet. This filter is
 * post-routing, and that is why it can compare `aud` against the already
 * resolved serviceId.
 */
@Component
public class ServiceAudienceFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ServiceAudienceFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);
        // Only type: service. A person token carries no aud, and must not (DEC-36).
        if (jwt == null || !"service".equals(jwt.getClaimAsString("type"))) {
            return chain.filter(exchange);
        }

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            // Should not happen: RoutePredicateHandlerMapping sets it BEFORE the
            // GlobalFilter chain starts. If it does happen, fail closed.
            log.error("GATEWAY_ROUTE_ATTR ausente en @Order(6) - ver PipelineOrderIT");
            return reject(exchange, "No se pudo determinar el destino.");
        }

        String destino = route.getUri().getHost() == null
                ? "" : route.getUri().getHost().toLowerCase(Locale.ROOT);
        List<String> aud = jwt.getAudience();

        if (aud == null || aud.isEmpty()) {
            log.warn("AUD_RECHAZADO reason=aud-ausente cliente={} destino={}",
                    jwt.getSubject(), destino);
            return reject(exchange, "El token de servicio no declara destino.");
        }
        if (!aud.contains(destino)) {
            log.warn("AUD_RECHAZADO reason=aud-no-coincide cliente={} aud={} destino={}",
                    jwt.getSubject(), aud, destino);
            return reject(exchange, "El token no fue emitido para este destino.");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, String detalle) {
        return ProblemDetails.write(exchange, HttpStatus.FORBIDDEN,
                ErrorTypes.INVALID_AUDIENCE, "Audiencia invalida", detalle);
    }

    @Override
    public int getOrder() {
        return 60;
    }
}
