package ar.edu.utn.frc.tup.p4.apigateway.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Paso 3 del pipeline · @Order(20).
 *
 * What it NEVER logs: bodies, tokens, the Authorization header, or the
 * clientSecret of /auth/token. A token in the log is a stolen token, for
 * whoever gets around to reading logs.
 *
 * The trace id is not logged by hand: it comes from logback-spring.xml's pattern.
 */
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long inicio = System.nanoTime();
        var req = exchange.getRequest();

        return chain.filter(exchange).doFinally(señal -> {
            var status = exchange.getResponse().getStatusCode();
            log.info("{} {} -> {} ({} ms)",
                    req.getMethod(), req.getPath().value(),
                    status == null ? "-" : status.value(),
                    (System.nanoTime() - inicio) / 1_000_000);
        });
    }

    @Override public int getOrder() { return 20; }
}
