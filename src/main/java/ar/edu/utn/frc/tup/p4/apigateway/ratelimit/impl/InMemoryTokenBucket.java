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

    /** Espera maxima que se anuncia: mas alla no es un Retry-After, es un "vuelva manana". */
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(60);

    private record State(double tokens, long lastNanos, int capacity, int refillPerMinute) { }

    /**
     * Con EVICCION: una entrada por key (ruta|IP) que nadie limpia es una fuga
     * lenta — cada IP distinta que alguna vez martilla una ruta cara vive para
     * siempre. 10 min sin uso o mas de 100.000 keys y se evicta; el siguiente
     * request arranca con el bucket lleno, que es lo correcto para una key
     * que no se ve hace 10 minutos.
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
     * Cuanto falta para el proximo token, calculado del refill real — no un
     * numero fijo. Es lo que el cliente lee en {@code Retry-After}.
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
        return Duration.ofSeconds(Math.min(Math.max(segundos, 1), ESPERA_MAXIMA.toSeconds()));
    }
}
