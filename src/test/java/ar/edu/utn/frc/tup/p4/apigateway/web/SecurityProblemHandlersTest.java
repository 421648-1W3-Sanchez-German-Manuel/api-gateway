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
    void sin_token_o_token_invalido_da_401_not_authenticated() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me"));
        StepVerifier.create(SecurityProblemHandlers.authenticationEntryPoint()
                        .commence(ex, new BadCredentialsException("firma invalida")))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/not-authenticated\"")
                // El motivo interno no sale al cuerpo (DEC-44).
                .doesNotContain("firma invalida");
    }

    @Test
    void autenticado_sin_acceso_da_403_access_denied() {
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
