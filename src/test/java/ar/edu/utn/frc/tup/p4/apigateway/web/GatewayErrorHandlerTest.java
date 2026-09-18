package ar.edu.utn.frc.tup.p4.apigateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    private final GatewayErrorHandler handler = new GatewayErrorHandler();

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me"));
    }

    private String handleAndReadBody(MockServerWebExchange ex, Throwable error) {
        StepVerifier.create(handler.handle(ex, error)).verifyComplete();
        return ex.getResponse().getBodyAsString().block();
    }

    @Test
    void an_unexpected_exception_comes_out_as_RFC_9457_and_not_as_an_empty_500() {
        // The whole pipeline answers problem+json. An unexpected exception used
        // to be the ONE hole: it came out as a 500 with no body and no
        // Content-Type, so the frontend had a branch it could not parse.
        var ex = exchange();
        String body = handleAndReadBody(ex, new IllegalStateException("boom"));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(body).contains("\"status\":500")
                .contains("\"type\":\"https://tpi.utn.frc/errors/unexpected-error\"");
    }

    @Test
    void the_exception_message_NEVER_reaches_the_body() {
        // Same criterion as GatewayProblemHandler: an unexpected exception is
        // exactly the one whose message carries stack frames, bean names or
        // connection strings.
        var ex = exchange();
        String body = handleAndReadBody(ex,
                new IllegalStateException("jdbc:postgresql://db:5432/users?password=hunter2"));

        assertThat(body).doesNotContain("jdbc").doesNotContain("hunter2")
                .contains("The request could not be processed.");
    }

    @Test
    void a_ResponseStatusException_keeps_its_own_mapping() {
        var ex = exchange();
        String body = handleAndReadBody(ex, new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body).contains("\"type\":\"https://tpi.utn.frc/errors/route-not-found\"");
    }
}
