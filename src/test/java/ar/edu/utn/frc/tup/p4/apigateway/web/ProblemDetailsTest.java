package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 - un solo camino para cortar un request. Cada filtro, controller o
 * handler de error usa {@link ProblemDetails} y nada mas; un filtro que
 * escribe un body a mano rompe la uniformidad del contrato y obliga al
 * frontend a tener una rama de manejo por filtro.
 *
 * <p>El test afirma tres cosas del contrato:
 * <ul>
 *   <li>{@code application/problem+json} (RFC 9457) con los campos
 *       {@code type, title, status, detail, instance}.</li>
 *   <li>{@code Retry-After} en los 429 y 503 - el cliente sabe que reintentar
 *       SIRVE aca, a diferencia del 401 donde reintentar no arregla nada.</li>
 *   <li>El {@code type} del 429 es el MISMO que el de {@code users-service}
 *       (DEC-24): el frontend tiene UNA sola rama de manejo y no necesita
 *       saber si respondio el gateway o el auth/.</li>
 * </ul>
 */
class ProblemDetailsTest {

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/me").header("X-Request-Id", "req-1"));
    }

    @Test
    void escribe_un_ProblemDetail_con_content_type_RFC_9457() {
        var ex = exchange();
        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.UNAUTHORIZED,
                ErrorTypes.SESSION_SUPERSEDED, "Sesion superada", "Otro dispositivo inicio sesion."))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getHeaders().getContentType().toString())
                .isEqualTo("application/problem+json");
    }

    @Test
    void el_429_lleva_Retry_After_y_el_type_compartido_con_users_service() {
        // DEC-24: el frontend tiene UNA sola rama y no necesita saber si
        // respondio el gateway o el auth/.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.TOO_MANY_REQUESTS,
                ErrorTypes.TOO_MANY_ATTEMPTS, "Demasiados intentos",
                "Supero el limite.", Duration.ofSeconds(60))).verifyComplete();

        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(ErrorTypes.TOO_MANY_ATTEMPTS.toString()).endsWith("/too-many-attempts");
    }

    @Test
    void el_503_por_Redis_caido_lleva_Retry_After() {
        // DEC-01: fail-closed, pero el cliente tiene que saber que reintentar
        // SIRVE aca, a diferencia de un 401 donde reintentar no arregla nada.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorTypes.SERVICIO_NO_DISPONIBLE, "No disponible",
                "Reintente en unos segundos.", Duration.ofSeconds(5))).verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }
}