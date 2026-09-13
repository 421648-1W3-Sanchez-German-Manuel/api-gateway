package ar.edu.utn.frc.tup.p4.apigateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayProblemHandlerTest {

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/loquesea"));
    }

    private String bodyOf(MockServerWebExchange ex, ResponseStatusException rse) {
        StepVerifier.create(GatewayProblemHandler.handle(ex, rse)).verifyComplete();
        return ex.getResponse().getBodyAsString().block();
    }

    @Test
    void el_404_mapea_a_route_not_found() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/route-not-found\"");
    }

    @Test
    void el_405_mapea_a_method_not_allowed() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/method-not-allowed\"");
    }

    @Test
    void el_403_mapea_a_access_denied() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/access-denied\"");
    }

    @Test
    void otro_status_sale_con_su_codigo_y_type_generico() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.BAD_REQUEST, "falta el campo xyz"));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body).contains("\"status\":400")
                .contains("unexpected-error");
    }

    @Test
    void el_mensaje_de_la_excepcion_NO_se_copia_al_cuerpo() {
        // Sanitizacion: el reason puede traer paths internos o datos del
        // request. El cuerpo lleva mensajes fijos, nada del framework.
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "secreto-interno-/etc/passwd-' OR '1'='1"));

        assertThat(body).doesNotContain("secreto-interno")
                .doesNotContain("passwd")
                .contains("La solicitud no pudo procesarse.");
    }
}
