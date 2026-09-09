package ar.edu.utn.frc.tup.p4.apigateway.repository;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.SessionCacheProperties;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository.EstadoSesion;
import ar.edu.utn.frc.tup.p4.apigateway.repository.impl.CachingSessionRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 - cache local de 3s sobre el repositorio Redis. La motivacion es
 * DISPONIBILIDAD, no carga: con DEC-01 fail-closed, Redis caido era
 * plataforma caida. Con esta cache, un hipo corto de Redis se absorbe y un
 * Redis caido se convierte en una degradacion acotada, no en un corte total.
 *
 * <p>El test es unitario y NO toca Redis: lo que se prueba es la politica
 * de cache - lo unico que esta en duda. La lectura contra Redis la prueba
 * L8 en su test de integracion.
 */
class CachingSessionRepositoryTest {

    private final SessionCacheProperties props = new SessionCacheProperties(Duration.ofSeconds(3), 100);

    @Test
    void dos_lecturas_seguidas_pegan_UNA_sola_vez_a_Redis() {
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet(); return Mono.just(new EstadoSesion.Vigente("sid-1")); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(1);
    }

    @Test
    void NO_cachea_el_estado_NoDisponible() {
        // Caching a Redis failure for 3 s turns a hiccup into a guaranteed 3 s
        // outage. Only what could actually be read gets cached.
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet();
                        return Mono.just(new EstadoSesion.NoDisponible(new RuntimeException("caido"))); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(2);
    }

    @Test
    void cachea_tambien_el_estado_Ausente() {
        // Un logout reciente es legitimo y frecuente: no hay por que pegarle a
        // Redis en cada request de una sesion ya cerrada.
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet(); return Mono.just(new EstadoSesion.Ausente()); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(1);
    }

    @Test
    void cada_usuario_tiene_su_propia_entrada() {
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet(); return Mono.just(new EstadoSesion.Vigente(id)); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u2")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(2);
    }
}