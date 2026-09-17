package ar.edu.utn.frc.tup.p4.apigateway.repository;

import reactor.core.publisher.Mono;

public interface SessionRepository {

    /**
     * DEC-01 - The three branches are a TYPE, not an Optional with a flag: the
     * compiler forces you to tell "no session" (401) apart from "Redis is not
     * responding" (503).
     *
     * With Optional<String> both look the same — empty — and that confusion is
     * exactly how an accidental fail-open is born.
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
