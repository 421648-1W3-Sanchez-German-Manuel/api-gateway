package ar.edu.utn.frc.tup.p4.apigateway.repository;

import reactor.core.publisher.Mono;

public interface SessionRepository {

    /**
     * DEC-01 - Las tres ramas son un TIPO, no un Optional con un flag: el
     * compilador obliga a distinguir "no hay sesion" (401) de "Redis no
     * responde" (503).
     *
     * Con Optional<String> las dos se ven igual — vacio — y esa confusion es
     * exactamente como nace un fail-open por accidente.
     */
    sealed interface SessionState {

        record Active(String sid) implements SessionState {
        }

        record Absent() implements SessionState {
        }

        record Unavailable(Throwable cause) implements SessionState {
        }
    }

    Mono<SessionState> findSid(String userId);
}
