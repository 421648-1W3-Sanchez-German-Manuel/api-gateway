package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.security.SessionValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionGuardTest {

    private final SessionValidator validator = mock(SessionValidator.class);
    private final SessionGuard guard = new SessionGuard(validator,
            new ar.edu.utn.frc.tup.p4.apigateway.routing.PublicRouteMatcher());

    private JwtAuthenticationToken person() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("type", "user").claim("sub", "u-1").claim("sid", "sid-vieja").build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
    }

    private record Run(boolean routed, MockServerWebExchange exchange) {
    }

    private Run through(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
        AtomicBoolean routed = new AtomicBoolean(false);
        WebFilterChain chain = e -> {
            routed.set(true);
            return Mono.empty();
        };

        StepVerifier.create(guard.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(person())))
                .verifyComplete();
        return new Run(routed.get(), exchange);
    }

    @Test
    void a_PUBLIC_route_does_NOT_get_its_session_checked() {
        // login / 2fa / refresh are the routes that CREATE or ROTATE the
        // session. A stale fu_at cookie -- from an already superseded session,
        // attached by the browser only because it shares the origin -- must not
        // reject a NEW login attempt.
        when(validator.verify(any())).thenReturn(Mono.just(SessionValidator.Decision.SUPERSEDED));

        Run run = through("/api/users/public/auth/login");

        assertThat(run.routed()).as("the request must reach the destination").isTrue();
        assertThat(run.exchange().getResponse().getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_PRIVATE_route_DOES_get_its_session_checked() {
        // The mirror of the test above: the skip must not open the guard for
        // everything.
        when(validator.verify(any())).thenReturn(Mono.just(SessionValidator.Decision.SUPERSEDED));

        Run run = through("/api/users/me");

        assertThat(run.routed()).as("the request must NOT reach the destination").isFalse();
        assertThat(run.exchange().getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
