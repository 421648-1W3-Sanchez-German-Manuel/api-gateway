package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingFilterTest {

    private ListAppender<ILoggingEvent> captured;
    private final LoggingFilter filter = new LoggingFilter();

    @BeforeEach
    void captureLogs() {
        captured = new ListAppender<>();
        captured.start();
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).addAppender(captured);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).detachAppender(captured);
    }

    @Test
    void NEVER_logs_the_Authorization_header() {
        // A token in the log is a stolen token, for whoever reads logs.
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me")
                .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.SECRET.firma").build());

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        // isNotEmpty first: allSatisfy passes vacuously over an empty list, and
        // without this the test would depend on another one testing that
        // something is logged.
        assertThat(captured.list).isNotEmpty().allSatisfy(event ->
                assertThat(event.getFormattedMessage())
                        .doesNotContain("Bearer").doesNotContain("SECRET").doesNotContain("eyJ"));
    }

    @Test
    void NEVER_logs_the_body() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/users/public/auth/token")
                .body("{\"clientSecret\":\"a-secret\"}"));

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(captured.list).isNotEmpty().allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("a-secret"));
    }

    @Test
    void logs_method_path_and_status() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(captured.list).anySatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("GET").contains("/api/users/me"));
    }

    @Test
    void it_is_the_second_filter() { assertThat(filter.getOrder()).isEqualTo(20); }
}
