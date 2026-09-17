package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** DEC-24 - the Gateway limits by IP; auth/ limits by email. */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(boolean enabled,
                                  List<ExpensiveRoute> expensiveRoutes,
                                  List<String> trustedProxies) {

    public record ExpensiveRoute(String path, String key, int capacity, int refillPerMinute) {
    }
}
