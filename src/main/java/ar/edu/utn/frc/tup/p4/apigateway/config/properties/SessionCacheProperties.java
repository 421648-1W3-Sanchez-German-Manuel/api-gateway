package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** DEC-25 - local cache of session:{userId}. Short TTL on purpose. */
@ConfigurationProperties(prefix = "gateway.session-cache")
public record SessionCacheProperties(Duration ttl, long maxSize) {
}
