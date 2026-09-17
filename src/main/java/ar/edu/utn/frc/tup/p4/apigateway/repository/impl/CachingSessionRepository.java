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
 * DEC-25 - in-PROCESS cache with a short TTL. It redefines "immediate
 * invalidation" from 0s to &lt;=3s: a deliberate departure from v5's text,
 * which said "without waiting the 10 min exp".
 *
 * <p>The risk it mitigates is NOT load: 120 concurrent users are ~1.200
 * GET/s against a Redis doing ~100.000 ops/s. It is AVAILABILITY: with
 * DEC-01 fail-closed, a dead Redis was a dead platform. With this cache, a
 * short Redis hiccup is absorbed and a dead Redis becomes a bounded
 * degradation, not a total outage.
 *
 * <p>{@link SessionState.Unavailable} is NOT cached: caching a Redis error
 * for 3s turns a hiccup into a guaranteed 3s outage.
 */
@Repository
@Primary
public class CachingSessionRepository implements SessionRepository {

    private final SessionRepository delegate;
    private final Cache<String, SessionState> cache;

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
    public Mono<SessionState> findSid(String userId) {
        SessionState cached = cache.getIfPresent(userId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return delegate.findSid(userId).doOnNext(status -> {
            // Unavailable NO: caching a Redis failure for 3s turns a hiccup
            // into a guaranteed 3s outage.
            if (!(status instanceof SessionState.Unavailable)) {
                cache.put(userId, status);
            }
        });
    }
}
