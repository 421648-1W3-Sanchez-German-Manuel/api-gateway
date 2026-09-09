package ar.edu.utn.frc.tup.p4.apigateway.repository.impl;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.SessionCacheProperties;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * DEC-25 - cache de PROCESO con TTL corto. Redefine "invalidacion inmediata"
 * de 0s a &lt;=3s: alejamiento deliberado del texto de v5, que decia "sin
 * esperar los 10 min de exp".
 *
 * <p>El riesgo que mitiga NO es carga: 120 usuarios concurrentes son
 * ~1.200 GET/s contra un Redis que hace ~100.000 ops/s. Es DISPONIBILIDAD:
 * con DEC-01 fail-closed, Redis caido era plataforma caida. Con esta cache,
 * un hipo corto de Redis se absorbe y un Redis caido se convierte en una
 * degradacion acotada, no en un corte total.
 *
 * <p>{@link EstadoSesion.NoDisponible} NO se cachea: cachear un error de
 * Redis por 3s convierte un hipo en una caida garantizada de 3s.
 */
@Repository
@Primary
public class CachingSessionRepository implements SessionRepository {

    private final SessionRepository delegate;
    private final Cache<String, EstadoSesion> cache;

    public CachingSessionRepository(
            @Qualifier("redisSessionRepository") SessionRepository delegate,
            SessionCacheProperties props) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(props.ttl())
                .maximumSize(props.maxSize())
                .build();
    }

    @Override
    public Mono<EstadoSesion> findSid(String userId) {
        EstadoSesion cacheado = cache.getIfPresent(userId);
        if (cacheado != null) {
            return Mono.just(cacheado);
        }
        return delegate.findSid(userId).doOnNext(status -> {
            // NoDisponible NO: cachear un fallo de Redis por 3s convierte un
            // hipo en una caida garantizada de 3s.
            if (!(status instanceof EstadoSesion.NoDisponible)) {
                cache.put(userId, status);
            }
        });
    }
}