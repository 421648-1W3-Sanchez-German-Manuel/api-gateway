package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Los umbrales de resiliencia, en CONFIG y no en codigo. Calibrar para carga
 * (RF-NFR-03, 120 concurrentes) es cambiar env y recrear el gateway, no un PR
 * con rebuild: {@code BULKHEAD_MAX_CONCURRENT=1 docker compose up -d
 * api-gateway} es la forma de comprobar que el bulkhead esta vivo.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.resilience")
public record ResilienceProperties(@Positive int breakerSlidingWindowSize,
                                   @Positive @Max(100) int breakerFailureRateThreshold,
                                   Duration breakerWaitOpen,
                                   @Positive int breakerHalfOpenCalls,
                                   Duration timeLimiterTimeout,
                                   Duration bulkheadRetryAfter,
                                   Duration fallbackRetryAfter) {
}
