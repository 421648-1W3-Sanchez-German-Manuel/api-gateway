package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** DEC-25 - cache local de session:{userId}. TTL corto a proposito. */
@ConfigurationProperties(prefix = "gateway.session-cache")
public record SessionCacheProperties(Duration ttl, long maxSize) {
}
