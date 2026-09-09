package ar.edu.utn.frc.tup.p4.apigateway.repository;

import reactor.core.publisher.Mono;

/**
 * Repositorio de la sesion unica (DEC-01). La unica operacion que el Gateway
 * hace contra Redis: leer el {@code sid} vigente para un {@code userId}.
 * Nunca escribe, nunca borra: {@code session:{userId}} lo escribe el login de
 * users-service y lo borra su logout y su desactivacion (DEC-22).
 *
 * <p>El resultado NO es {@code Optional<String>} a proposito: con Optional
 * las tres ramas de DEC-01 (vigente con sid, ausente, Redis caido) se
 * confunden - "ausente" y "Redis caido" son las dos vacias. Como TIPO
 * sealed, el compilador obliga a distinguirlas, y un 401 dirigido a alguien
 * cuya sesion esta bien - porque Redis se cayo - deja de ser posible.
 */
public interface SessionRepository {

    Mono<EstadoSesion> findSid(String userId);

    /**
     * Tres ramas, no mas, no menos. Si aparece una cuarta hay que cambiar
     * esta clase y todos los {@code switch} que la consumen.
     */
    sealed interface EstadoSesion {
        record Vigente(String sid) implements EstadoSesion { }
        record Ausente() implements EstadoSesion { }
        record NoDisponible(Throwable causa) implements EstadoSesion { }
    }
}