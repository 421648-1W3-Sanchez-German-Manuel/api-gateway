package ar.edu.utn.frc.tup.p4.apigateway.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    private final GatewayErrorHandler handler = new GatewayErrorHandler();

    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLogs() {
        captured = new ListAppender<>();
        captured.start();
        ((Logger) LoggerFactory.getLogger(GatewayErrorHandler.class)).addAppender(captured);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(GatewayErrorHandler.class)).detachAppender(captured);
    }

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

    @Test
    void a_client_that_HUNG_UP_is_not_reported_as_a_server_error() {
        // A browser navigating away mid-request, a dropped mobile connection:
        // routine under load and not a gateway failure. Logging each one at
        // ERROR with a stack trace buries the failures that ARE unexpected,
        // which is the only reason that log line exists.
        var ex = exchange();
        StepVerifier.create(handler.handle(ex,
                new java.io.IOException("Connection reset by peer"))).verifyComplete();

        assertThat(captured.list)
                .as("a disconnected client must not raise an ERROR")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    void an_unexpected_exception_IS_still_reported_as_a_server_error() {
        // The mirror of the test above: quieting disconnects must not quiet
        // everything.
        var ex = exchange();
        StepVerifier.create(handler.handle(ex, new IllegalStateException("boom"))).verifyComplete();

        assertThat(captured.list).anyMatch(event -> event.getLevel() == Level.ERROR);
    }
}
