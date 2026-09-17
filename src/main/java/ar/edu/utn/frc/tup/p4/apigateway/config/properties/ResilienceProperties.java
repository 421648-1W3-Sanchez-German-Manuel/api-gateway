package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The resilience thresholds, in CONFIG and not in code. Calibrating for load
 * (RF-NFR-03, 120 concurrent) is changing env and recreating the gateway, not
 * a PR with a rebuild: {@code BULKHEAD_MAX_CONCURRENT=1 docker compose up -d
 * api-gateway} is the way to check that the bulkhead is alive.
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
