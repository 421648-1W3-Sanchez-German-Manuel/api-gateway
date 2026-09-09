package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** DEC-24 - el Gateway limita por IP; auth/ limita por email. */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(boolean enabled,
                                  List<RutaCara> expensiveRoutes,
                                  List<String> trustedProxies) {

    public record RutaCara(String path, String key, int capacity, int refillPerMinute) {
    }
}
