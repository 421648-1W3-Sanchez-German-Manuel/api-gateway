package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Where an account that is NOT enabled can talk to. Today everything exempt
 * belongs to users-service, so the gateway needs to know no foreign routes:
 * if a new micro brings its own onboarding, its prefix is added here (or via
 * env) instead of touching code.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.account-gate")
public record AccountGateProperties(@NotEmpty List<String> exemptPrefixes) {
}
