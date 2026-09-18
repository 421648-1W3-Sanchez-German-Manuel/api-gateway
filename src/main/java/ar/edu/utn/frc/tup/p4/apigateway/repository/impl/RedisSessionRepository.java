package ar.edu.utn.frc.tup.p4.apigateway.repository.impl;

import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * The ONLY read the Gateway does against Redis. It never writes, never deletes:
 * {@code session:{userId}} is written by users-service's login and deleted by
 * its logout and its deactivation (DEC-22).
 *
 * <p>DEC-01 - fail-closed, telling the two causes apart:
 * <ul>
 *   <li>key present -> {@link SessionState.Active}.</li>
 *   <li>key absent -> {@link SessionState.Absent}.</li>
 *   <li>Redis error -> {@link SessionState.Unavailable}, NOT {@code Absent}.
 *       Confusing them is fail-open disguised as fail-closed: someone with a
 *       perfectly valid session would be logged out only because Redis went
 *       down.</li>
 * </ul>
 */
@Repository
public class RedisSessionRepository implements SessionRepository {

    private final ReactiveStringRedisTemplate redis;

    public RedisSessionRepository(ReactiveStringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Mono<SessionState> findSid(String userId) {
        return redis.opsForValue().get("session:" + userId)
                .map(sid -> (SessionState) new SessionState.Active(sid))
                .defaultIfEmpty(new SessionState.Absent())
                .onErrorResume(e -> Mono.just(new SessionState.Unavailable(e)));
    }
}
