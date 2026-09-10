package ar.edu.utn.frc.tup.p4.apigateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * DEC-42 - standard values are in place; calibration is a later adjustment, not
 * a prerequisite. A system with conservative thresholds is loosened by looking
 * at metrics; one with no limits has nowhere to start measuring.
 *
 * What to watch after the first load test: the 429 rate on legitimate traffic
 * (if > 0, loosen) and the breaker's open rate (if it opens without anything
 * being actually down, raise slidingWindowSize).
 */
@Configuration
public class ResilienceConfig {

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> defaults() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(20)          // ~1 s of traffic at 120 concurrent users
                        .failureRateThreshold(50)       // reacts fast without opening on two errors
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        // LOWER than the client's timeout: if the browser gives
                        // up at 5 s and the gateway at 10, the user sees a generic
                        // error and the gateway keeps a thread busy for nothing.
                        .timeoutDuration(Duration.ofSeconds(3))
                        .build())
                .build());
    }
}
