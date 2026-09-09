package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gateway.identity")
public record IdentityHeaderProperties(List<String> reservedHeaders) {
}
