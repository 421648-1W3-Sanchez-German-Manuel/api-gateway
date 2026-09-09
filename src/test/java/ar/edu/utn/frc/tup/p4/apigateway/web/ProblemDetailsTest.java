package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsTest {

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/me").header("X-Request-Id", "req-1"));
    }

    @Test
    void escribe_un_ProblemDetail_con_content_type_RFC_9457() {
        var ex = exchange();
        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.UNAUTHORIZED,
                        ErrorTypes.SESSION_SUPERSEDED, "Session superseded",
                        "Otro dispositivo inicio sesion."))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getHeaders().getContentType())
                .hasToString("application/problem+json");
    }

    @Test
    void el_429_lleva_Retry_After_y_el_type_compartido_con_users_service() {
        // DEC-24: el frontend tiene UNA sola rama de manejo y no necesita
        // saber si contesto el Gateway o auth/.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.TOO_MANY_REQUESTS,
                        ErrorTypes.TOO_MANY_ATTEMPTS, "Too many attempts",
                        "Supero el limite.", Duration.ofSeconds(60)))
                .verifyComplete();

        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(ErrorTypes.TOO_MANY_ATTEMPTS.toString()).endsWith("/too-many-attempts");
    }

    @Test
    void el_503_por_Redis_caido_lleva_Retry_After() {
        // DEC-01: fail-closed, pero el cliente tiene que saber que reintentar
        // sirve — a diferencia del 401, donde reintentar no arregla nada.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorTypes.SERVICE_UNAVAILABLE, "No disponible",
                        "Reintente en unos segundos.", Duration.ofSeconds(5)))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void las_claves_extra_del_exchange_terminan_en_el_cuerpo() {
        // AccountStateGuard agrega accountStatus, que es lo que el frontend usa
        // para decidir a que pantalla mandar a la persona.
        var ex = exchange();
        ex.getAttributes().put(ProblemDetails.ATTR_EXTRAS,
                Map.of("accountStatus", "PENDING_COURSE"));

        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.FORBIDDEN,
                        ErrorTypes.PENDING_ACCOUNT, "Cuenta pending", "No esta activa."))
                .verifyComplete();

        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"accountStatus\":\"PENDING_COURSE\"");
    }

    @Test
    void el_cuerpo_lleva_el_requestId_para_que_el_usuario_lo_pueda_reportar() {
        var ex = exchange();
        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.UNAUTHORIZED,
                        ErrorTypes.NOT_AUTHENTICATED, "No autenticado", "Sin token."))
                .verifyComplete();

        assertThat(ex.getResponse().getBodyAsString().block())
                .contains("\"requestId\":\"req-1\"")
                .contains("\"instance\":\"/api/users/me\"");
    }
}
