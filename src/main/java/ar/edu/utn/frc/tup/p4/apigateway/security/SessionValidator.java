package ar.edu.utn.frc.tup.p4.apigateway.security;

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
 * NO es un OAuth2TokenValidator, y NO PUEDE SERLO. Esa interfaz es SINCRONICA:
 * `OAuth2TokenValidatorResult validate(Jwt)`. Verificar la sesion exige leer
 * Redis, que es I/O, y el unico modo de meter I/O reactiva en una firma
 * sincronica es `.block()`.
 *
 * Reactor lo PROHIBE sobre el event loop de Netty, que es donde el
 * NimbusReactiveJwtDecoder corre sus validators. No es que ande lento: tira
 * IllegalStateException, que NO es una AuthenticationException, asi que no pasa
 * por el authenticationEntryPoint y sale como un 500 crudo. Todo token que
 * decodifica bien pero deberia rechazarse contesta 500 en vez de 401 o 503.
 *
 * Por eso esta clase solo DECIDE, devolviendo un Mono, y la decision la aplica
 * un WebFilter reactivo (`SessionGuard`, Step 5).
 *
 * DEC-01 - fail-closed distinguiendo la causa. La rama Unavailable se señaliza
 * distinto para que el filtro conteste 503 y no 401: decirle "tu sesion vencio"
 * a alguien cuya sesion esta perfecta, porque se cayo Redis, lo manda a
 * re-loguearse al pedo.
 */
@Component
public class SessionValidator {

    /** La decision, ya traducida. El filtro la mapea a una respuesta. */
    public enum Resultado { VIGENTE, SUPERADA, CERRADA, NO_VERIFICABLE }

    private static final Logger log = LoggerFactory.getLogger(SessionValidator.class);

    private final SessionRepository sessions;

    public SessionValidator(SessionRepository sessions) { this.sessions = sessions; }

    public Mono<Resultado> verificar(Jwt jwt) {
        if (!"user".equals(jwt.getClaimAsString("type"))) {
            return Mono.just(Resultado.VIGENTE);   // un token de servicio no lleva sid
        }

        String sidToken = jwt.getClaimAsString("sid");
        if (sidToken == null) {
            log.warn("JWT_RECHAZADO reason=claim-ausente claim=sid");
            return Mono.just(Resultado.CERRADA);
        }

        return sessions.findSid(jwt.getSubject()).map(status -> switch (status) {
            case SessionState.Active v when v.sid().equals(sidToken) -> Resultado.VIGENTE;
            case SessionState.Active v -> {
                log.warn("JWT_RECHAZADO reason=session-superseded sub={}", jwt.getSubject());
                yield Resultado.SUPERADA;
            }
            case SessionState.Absent ignored -> {
                log.warn("JWT_RECHAZADO reason=session-closed sub={}", jwt.getSubject());
                yield Resultado.CERRADA;
            }
            case SessionState.Unavailable nd -> {
                log.error("SESION_NO_VERIFICABLE sub={} - Redis no responde",
                        jwt.getSubject(), nd.cause());
                yield Resultado.NO_VERIFICABLE;
            }
        });
    }
}
