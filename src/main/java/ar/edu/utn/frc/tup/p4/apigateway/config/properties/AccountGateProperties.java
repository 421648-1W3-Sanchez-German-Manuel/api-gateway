package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * A donde puede hablar una cuenta NO habilitada. Hoy todo lo exento es de
 * users-service, asi que el gateway no necesita conocer rutas ajenas: si un
 * micro nuevo trae su propio onboarding, se agrega su prefijo aca (o por env)
 * en vez de tocar codigo.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.account-gate")
public record AccountGateProperties(@NotEmpty List<String> exemptPrefixes) {
}
