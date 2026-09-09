package ar.edu.utn.frc.tup.p4.apigateway.repository.impl;

import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * La UNICA lectura que el Gateway hace contra Redis. Nunca escribe, nunca
 * borra: {@code session:{userId}} lo escribe el login de users-service y lo
 * borra su logout y su desactivacion (DEC-22).
 *
 * <p>DEC-01 - fail-closed distinguiendo causa:
 * <ul>
 *   <li>key presente -> {@link SessionState.Active}.</li>
 *   <li>key ausente -> {@link SessionState.Absent}.</li>
 *   <li>error de Redis -> {@link SessionState.Unavailable}, NO {@code Absent}.
 *       Confundirlos es fail-open disfrazado de fail-closed: alguien con sesion
 *       perfectamente valida seria deslogueado solo porque Redis se cayo.</li>
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