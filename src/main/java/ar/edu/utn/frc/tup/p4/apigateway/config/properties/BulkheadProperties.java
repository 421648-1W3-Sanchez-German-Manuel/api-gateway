package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * DEC-42 - concurrencia maxima POR DESTINO. El breaker mide fallas, el bulkhead
 * mide ocupacion: un destino que todavia no falla pero tarda 3 s por request se
 * come el pool del Gateway entero antes de que la tasa de error llegue al 50%
 * que abre el breaker. Son dos fallas distintas y hacen falta las dos.
 *
 * @param enabled            apagarlo deja pasar todo, sin limite de concurrencia
 * @param maxConcurrentCalls requests en vuelo simultaneas por serviceId destino
 * @param maxWait            cuanto espera una request por un permiso antes de
 *                           rechazarse. 0 = rechaza en el acto (fail fast)
 */
@Validated
@ConfigurationProperties(prefix = "gateway.bulkhead")
public record BulkheadProperties(boolean enabled,
                                 @Positive int maxConcurrentCalls,
                                 Duration maxWait) {
}
