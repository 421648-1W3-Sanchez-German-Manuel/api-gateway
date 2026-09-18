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
    void writes_a_ProblemDetail_with_RFC_9457_content_type() {
        var ex = exchange();
        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.UNAUTHORIZED,
                        ErrorTypes.SESSION_SUPERSEDED, "Session superseded",
                        "Another device signed in."))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getHeaders().getContentType())
                .hasToString("application/problem+json");
    }

    @Test
    void code_429_carries_Retry_After_and_the_type_shared_with_users_service() {
        // DEC-24: the frontend has ONE handling branch and does not need to
        // know whether the Gateway or auth/ answered.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.TOO_MANY_REQUESTS,
                        ErrorTypes.TOO_MANY_ATTEMPTS, "Too many attempts",
                        "You exceeded the limit.", Duration.ofSeconds(60)))
                .verifyComplete();

        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(ErrorTypes.TOO_MANY_ATTEMPTS.toString()).endsWith("/too-many-attempts");
    }

    @Test
    void code_503_for_a_down_Redis_carries_Retry_After() {
        // DEC-01: fail-closed, but the client has to know that retrying helps
        // — unlike the 401, where retrying fixes nothing.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorTypes.SERVICE_UNAVAILABLE, "Unavailable",
                        "Retry in a few seconds.", Duration.ofSeconds(5)))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void the_extra_exchange_keys_end_up_in_the_body() {
        // AccountStateGuard adds accountStatus, which is what the frontend uses
        // to decide which screen to send the person to.
        var ex = exchange();
        ex.getAttributes().put(ProblemDetails.ATTR_EXTRAS,
                Map.of("accountStatus", "PENDING_COURSE"));

        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.FORBIDDEN,
                        ErrorTypes.PENDING_ACCOUNT, "Account pending", "It is not active."))
                .verifyComplete();

        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"accountStatus\":\"PENDING_COURSE\"");
    }

    @Test
    void the_body_carries_the_requestId_so_the_user_can_report_it() {
        var ex = exchange();
        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.UNAUTHORIZED,
                        ErrorTypes.NOT_AUTHENTICATED, "Not authenticated", "No token."))
                .verifyComplete();

        assertThat(ex.getResponse().getBodyAsString().block())
                .contains("\"requestId\":\"req-1\"")
                .contains("\"instance\":\"/api/users/me\"");
    }
}
