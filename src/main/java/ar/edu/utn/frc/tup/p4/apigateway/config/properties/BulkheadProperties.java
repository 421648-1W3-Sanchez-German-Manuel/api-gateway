package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * DEC-42 - maximum concurrency PER DESTINATION. The breaker measures
 * failures, the bulkhead measures occupancy: a destination that has not
 * failed yet but takes 3 s per request eats the whole Gateway pool before the
 * error rate reaches the 50% that opens the breaker. They are two different
 * failures and both are needed.
 *
 * @param enabled            turning it off lets everything through, no
 *                           concurrency limit
 * @param maxConcurrentCalls simultaneous in-flight requests per destination
 *                           serviceId
 * @param maxWait            how long a request waits for a permit before it
 *                           is rejected. 0 = rejects on the spot (fail fast)
 */
@Validated
@ConfigurationProperties(prefix = "gateway.bulkhead")
public record BulkheadProperties(boolean enabled,
                                 @Positive int maxConcurrentCalls,
                                 Duration maxWait) {
}
