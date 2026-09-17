package ar.edu.utn.frc.tup.p4.apigateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityProblemHandlersTest {

    @Test
    void no_token_or_invalid_token_gives_401_not_authenticated() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me"));
        StepVerifier.create(SecurityProblemHandlers.authenticationEntryPoint()
                        .commence(ex, new BadCredentialsException("invalid signature")))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/not-authenticated\"")
                // The internal reason does not go out in the body (DEC-44).
                .doesNotContain("invalid signature");
    }

    @Test
    void authenticated_without_access_gives_403_access_denied() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me"));
        StepVerifier.create(SecurityProblemHandlers.accessDeniedHandler()
                        .handle(ex, new AccessDeniedException("not allowed by rule X")))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/access-denied\"")
                .contains("\"status\":403")
                .doesNotContain("rule X");
    }
}
