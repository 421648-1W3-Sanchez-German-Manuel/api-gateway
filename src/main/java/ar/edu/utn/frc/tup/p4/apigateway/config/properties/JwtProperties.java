package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DEC-07 - "users-service" is a logical name, not a URL. */
@ConfigurationProperties(prefix = "gateway.jwt")
public record JwtProperties(String expectedIssuer) {
}
