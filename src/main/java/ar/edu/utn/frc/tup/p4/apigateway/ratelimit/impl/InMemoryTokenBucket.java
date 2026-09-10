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

    private record Estado(double fichas, long ultimoNanos) { }

    private final Map<String, Estado> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean consume(String key, int capacidad, int recargaPorMinuto) {
        long ahora = System.nanoTime();
        // compute() solo puede devolver el nuevo ESTADO, no si se permitio o no
        // el request: el resultado se guarda aparte, en el mismo compute atomico
        // que decide el estado, para no perder la decision entre hilos.
        boolean[] permitido = new boolean[1];
        buckets.compute(key, (k, previo) -> {
            double disponibles = previo == null
                    ? capacidad
                    : Math.min(capacidad,
                        previo.fichas() + (ahora - previo.ultimoNanos()) / 60_000_000_000.0 * recargaPorMinuto);
            permitido[0] = disponibles >= 1;
            return new Estado(permitido[0] ? disponibles - 1 : disponibles, ahora);
        });
        return permitido[0];
    }

    @Override
    public Duration suggestedWait(String key) { return Duration.ofSeconds(60); }
}
