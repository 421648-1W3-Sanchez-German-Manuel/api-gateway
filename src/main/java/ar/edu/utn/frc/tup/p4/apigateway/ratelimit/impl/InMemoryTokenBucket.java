package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.TokenBucket;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * An in-memory bucket, per instance. A conscious decision: a distributed
 * limiter in Redis would add one write per request to the critical path, and
 * this limit is a flood guard - with N instances the real ceiling is
 * N × capacity, which is still a ceiling.
 */
@Component
public class InMemoryTokenBucket implements TokenBucket {

    /** Maximum wait that is advertised: beyond that it is not a Retry-After, it is a "come back tomorrow". */
    private static final Duration MAX_ADVERTISED_WAIT = Duration.ofSeconds(60);

    private record State(double tokens, long lastNanos, int capacity, int refillPerMinute) { }

    /**
     * With EVICTION: an entry per key (route|IP) that nobody cleans is a slow
     * leak — every distinct IP that ever hammers an expensive route lives
     * forever. 10 min without use or more than 100.000 keys and it is evicted;
     * the next request starts with a full bucket, which is the right thing for
     * a key that has not been seen in 10 minutes.
     */
    private final Cache<String, State> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    @Override
    public boolean consume(String key, int capacity, int refillPerMinute) {
        long now = System.nanoTime();
        // compute() can only return the new STATE, not whether the request was
        // allowed: the decision is captured separately, inside the same atomic
        // compute that decides the state, so it can't be lost across threads.
        boolean[] allowed = new boolean[1];
        buckets.asMap().compute(key, (k, previous) -> {
            double available = previous == null
                    ? capacity
                    : Math.min(capacity,
                        previous.tokens() + (now - previous.lastNanos()) / 60_000_000_000.0 * refillPerMinute);
            allowed[0] = available >= 1;
            return new State(allowed[0] ? available - 1 : available, now, capacity, refillPerMinute);
        });
        return allowed[0];
    }

    /**
     * How long until the next token, computed from the real refill — not a
     * fixed number. It is what the client reads in {@code Retry-After}.
     */
    @Override
    public Duration suggestedWait(String key) {
        State s = buckets.getIfPresent(key);
        if (s == null || s.refillPerMinute() <= 0) {
            return Duration.ZERO;
        }
        long elapsedNanos = Math.max(0, System.nanoTime() - s.lastNanos());
        double available = Math.min(s.capacity(),
                s.tokens() + elapsedNanos / 60_000_000_000.0 * s.refillPerMinute());
        if (available >= 1) {
            return Duration.ofSeconds(1);
        }
        long segundos = (long) Math.ceil((1 - available) / s.refillPerMinute() * 60);
        return Duration.ofSeconds(Math.min(Math.max(segundos, 1), MAX_ADVERTISED_WAIT.toSeconds()));
    }
}
