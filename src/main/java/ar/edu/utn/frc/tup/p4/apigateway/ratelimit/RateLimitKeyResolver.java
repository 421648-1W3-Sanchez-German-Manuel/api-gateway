package ar.edu.utn.frc.tup.p4.apigateway.ratelimit;

import org.springframework.web.server.ServerWebExchange;

public interface RateLimitKeyResolver {
    String resolve(ServerWebExchange exchange, String keyType);
}
