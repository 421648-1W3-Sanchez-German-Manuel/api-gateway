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
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/whatever"));
    }

    private String bodyOf(MockServerWebExchange ex, ResponseStatusException rse) {
        StepVerifier.create(GatewayProblemHandler.handle(ex, rse)).verifyComplete();
        return ex.getResponse().getBodyAsString().block();
    }

    @Test
    void code_404_maps_to_route_not_found() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/route-not-found\"");
    }

    @Test
    void code_405_maps_to_method_not_allowed() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/method-not-allowed\"");
    }

    @Test
    void code_403_maps_to_access_denied() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/access-denied\"");
    }

    @Test
    void another_status_goes_out_with_its_code_and_a_generic_type() {
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing field xyz"));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body).contains("\"status\":400")
                .contains("unexpected-error");
    }

    @Test
    void the_exception_message_is_NOT_copied_to_the_body() {
        // Sanitization: the reason can carry internal paths or request data.
        // The body carries fixed messages, nothing from the framework.
        var ex = exchange();
        String body = bodyOf(ex, new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "internal-secret-/etc/passwd-' OR '1'='1"));

        assertThat(body).doesNotContain("internal-secret")
                .doesNotContain("passwd")
                .contains("The request could not be processed.");
    }
}
