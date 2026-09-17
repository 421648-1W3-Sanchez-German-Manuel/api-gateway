package ar.edu.utn.frc.tup.p4.apigateway.repository;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.SessionCacheProperties;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository.SessionState;
import ar.edu.utn.frc.tup.p4.apigateway.repository.impl.CachingSessionRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 - local 3s cache on top of the Redis repository. The motivation is
 * AVAILABILITY, not load: with DEC-01 fail-closed, a dead Redis was a dead
 * platform. With this cache, a short Redis hiccup is absorbed and a dead
 * Redis becomes a bounded degradation, not a total outage.
 *
 * <p>The test is unit-level and does NOT touch Redis: what is tested is the
 * cache policy - the only thing in doubt. The read against Redis is tested by
 * L8 in its integration test.
 */
class CachingSessionRepositoryTest {

    private final SessionCacheProperties props = new SessionCacheProperties(Duration.ofSeconds(3), 100);

    @Test
    void two_consecutive_reads_hit_Redis_ONLY_once() {
        AtomicInteger calls = new AtomicInteger();
        var cached = new CachingSessionRepository(
                id -> { calls.incrementAndGet(); return Mono.just(new SessionState.Active("sid-1")); },
                props);

        StepVerifier.create(cached.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cached.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void does_NOT_cache_the_Unavailable_state() {
        // Caching a Redis failure for 3 s turns a hiccup into a guaranteed 3 s
        // outage. Only what could actually be read gets cached.
        AtomicInteger calls = new AtomicInteger();
        var cached = new CachingSessionRepository(
                id -> { calls.incrementAndGet();
                        return Mono.just(new SessionState.Unavailable(new RuntimeException("down"))); },
                props);

        StepVerifier.create(cached.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cached.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void it_also_caches_the_Absent_state() {
        // A recent logout is legitimate and frequent: there is no reason to hit
        // Redis on every request of an already closed session.
        AtomicInteger calls = new AtomicInteger();
        var cached = new CachingSessionRepository(
                id -> { calls.incrementAndGet(); return Mono.just(new SessionState.Absent()); },
                props);

        StepVerifier.create(cached.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cached.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void each_user_has_its_own_entry() {
        AtomicInteger calls = new AtomicInteger();
        var cached = new CachingSessionRepository(
                id -> { calls.incrementAndGet(); return Mono.just(new SessionState.Active(id)); },
                props);

        StepVerifier.create(cached.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cached.findSid("u2")).expectNextCount(1).verifyComplete();

        assertThat(calls.get()).isEqualTo(2);
    }
}
