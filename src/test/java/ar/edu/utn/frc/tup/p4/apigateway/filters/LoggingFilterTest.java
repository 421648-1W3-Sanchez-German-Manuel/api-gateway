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
    private final LoggingFilter filtro = new LoggingFilter();

    @BeforeEach
    void capturarLogs() {
        captured = new ListAppender<>();
        captured.start();
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).addAppender(captured);
    }

    @AfterEach
    void soltar() {
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).detachAppender(captured);
    }

    @Test
    void NUNCA_loguea_el_header_Authorization() {
        // A token in the log is a stolen token, for whoever reads logs.
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me")
                .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.SECRETO.firma").build());

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        // isNotEmpty primero: allSatisfy pasa vacuamente sobre una lista vacia,
        // y sin esto el test dependeria de que otro pruebe que se loguea algo.
        assertThat(captured.list).isNotEmpty().allSatisfy(evento ->
                assertThat(evento.getFormattedMessage())
                        .doesNotContain("Bearer").doesNotContain("SECRETO").doesNotContain("eyJ"));
    }

    @Test
    void NUNCA_loguea_el_body() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/users/public/auth/token")
                .body("{\"clientSecret\":\"un-secreto\"}"));

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(captured.list).isNotEmpty().allSatisfy(evento ->
                assertThat(evento.getFormattedMessage()).doesNotContain("un-secreto"));
    }

    @Test
    void loguea_metodo_path_y_status() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(captured.list).anySatisfy(evento ->
                assertThat(evento.getFormattedMessage()).contains("GET").contains("/api/users/me"));
    }

    @Test
    void es_el_segundo_filtro() { assertThat(filtro.getOrder()).isEqualTo(20); }
}
