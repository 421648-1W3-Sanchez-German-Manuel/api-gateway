package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.TokenBucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory bucket, per instance. A conscious decision: a distributed
 * limiter in Redis would add one write per request to the critical path, and
 * this limit is a flood guard - with N instances the real ceiling is
 * N × capacity, which is still a ceiling.
 */
@Component
public class InMemoryTokenBucket implements TokenBucket {

    private record State(double tokens, long lastNanos) { }

    private final Map<String, State> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean consume(String key, int capacity, int refillPerMinute) {
        long now = System.nanoTime();
        // compute() can only return the new STATE, not whether the request was
        // allowed: the decision is captured separately, inside the same atomic
        // compute that decides the state, so it can't be lost across threads.
        boolean[] allowed = new boolean[1];
        buckets.compute(key, (k, previous) -> {
            double available = previous == null
                    ? capacity
                    : Math.min(capacity,
                        previous.tokens() + (now - previous.lastNanos()) / 60_000_000_000.0 * refillPerMinute);
            allowed[0] = available >= 1;
            return new State(allowed[0] ? available - 1 : available, now);
        });
        return allowed[0];
    }

    @Override
    public Duration suggestedWait(String key) { return Duration.ofSeconds(60); }
}
