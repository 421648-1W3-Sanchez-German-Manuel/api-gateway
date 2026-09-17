package ar.edu.utn.frc.tup.p4.apigateway.security;

import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * v5 - single session. It is the gateway's ONLY Redis read.
 * It only applies to `type: user`: service tokens carry no `sid`.
 *
 * It is NOT an OAuth2TokenValidator, and it CANNOT be one. That interface is
 * synchronous: `OAuth2TokenValidatorResult validate(Jwt)`. Verifying the
 * session requires reading Redis, which is I/O, and the only way to put
 * reactive I/O in a synchronous signature is `.block()`.
 *
 * Reactor FORBIDS that on Netty's event loop, which is where
 * NimbusReactiveJwtDecoder runs its validators. It is not that it is slow: it
 * throws IllegalStateException, which is NOT an AuthenticationException, so it
 * does not go through the authenticationEntryPoint and comes out as a raw 500.
 * Every token that decodes fine but should be rejected answers 500 instead of
 * 401 or 503.
 *
 * That is why this class only DECIDES, returning a Mono, and a reactive
 * WebFilter (`SessionGuard`, Step 5) applies the decision.
 *
 * DEC-01 - fail-closed, telling the two causes apart. The Unavailable branch is
 * signaled differently so that the filter answers 503 instead of 401: telling
 * someone whose session is perfectly fine that "your session expired", because
 * Redis went down, sends them to re-login for nothing.
 */
@Component
public class SessionValidator {

    /** The decision, already translated. The filter maps it to a response. */
    public enum Decision { VALID, SUPERSEDED, CLOSED, UNVERIFIABLE }

    private static final Logger log = LoggerFactory.getLogger(SessionValidator.class);

    private final SessionRepository sessions;

    public SessionValidator(SessionRepository sessions) { this.sessions = sessions; }

    public Mono<Decision> verify(Jwt jwt) {
        if (!PrincipalType.USER.matches(jwt.getClaimAsString("type"))) {
            return Mono.just(Decision.VALID);   // a service token carries no sid
        }

        String sidToken = jwt.getClaimAsString("sid");
        if (sidToken == null) {
            log.warn("JWT_REJECTED reason=claim-missing claim=sid");
            return Mono.just(Decision.CLOSED);
        }

        return sessions.findSid(jwt.getSubject()).map(status -> switch (status) {
            case SessionState.Active v when v.sid().equals(sidToken) -> Decision.VALID;
            case SessionState.Active v -> {
                log.warn("JWT_REJECTED reason=session-superseded sub={}", jwt.getSubject());
                yield Decision.SUPERSEDED;
            }
            case SessionState.Absent ignored -> {
                log.warn("JWT_REJECTED reason=session-closed sub={}", jwt.getSubject());
                yield Decision.CLOSED;
            }
            case SessionState.Unavailable nd -> {
                log.error("SESSION_UNVERIFIABLE sub={} - Redis is not responding",
                        jwt.getSubject(), nd.cause());
                yield Decision.UNVERIFIABLE;
            }
        });
    }
}
