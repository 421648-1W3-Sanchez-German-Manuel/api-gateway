package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private ServerWebExchange run(MockServerHttpRequest req) {
        var ex = MockServerWebExchange.from(req);
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        GatewayFilterChain chain = e -> { seen.set(e); return Mono.empty(); };
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        return seen.get();
    }

    private ContextView runAndCaptureContext(MockServerHttpRequest req) {
        var ex = MockServerWebExchange.from(req);
        AtomicReference<ContextView> context = new AtomicReference<>();
        GatewayFilterChain chain = e -> Mono.deferContextual(ctx -> {
            context.set(ctx);
            return Mono.empty();
        });
        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
        return context.get();
    }

    @Test
    void generates_an_X_Request_Id_when_missing() {
        var mutated = run(MockServerHttpRequest.get("/api/users/me").build());
        assertThat(mutated.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID))
                .isNotBlank();
    }

    @Test
    void PRESERVES_the_incoming_traceparent() {
        // W3C Trace Context: if another service already started the trace, we
        // do not overwrite it - that splits the trail in two right at the edge.
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var mutated = run(MockServerHttpRequest.get("/api/users/me")
                .header("traceparent", traceparent).build());

        assertThat(mutated.getRequest().getHeaders().getFirst("traceparent")).isEqualTo(traceparent);
    }

    @Test
    void an_X_Request_Id_with_a_newline_is_REGENERATED_not_propagated() {
        // Log-line injection: the id ends up verbatim in the MDC and in the
        // response header. Anything outside the allowlist is discarded.
        var mutated = run(MockServerHttpRequest.get("/api/users/me")
                .header(IdentityHeaders.REQUEST_ID, "abc\nfake LINE-INJECTED").build());

        String propagated = mutated.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID);
        assertThat(propagated).doesNotContain("\n").doesNotContain("INJECTED");
    }

    @Test
    void a_giant_X_Request_Id_is_REGENERATED() {
        var mutated = run(MockServerHttpRequest.get("/api/users/me")
                .header(IdentityHeaders.REQUEST_ID, "a".repeat(4096)).build());

        assertThat(mutated.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID))
                .hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void a_malformed_traceparent_is_REGENERATED() {
        var mutated = run(MockServerHttpRequest.get("/api/users/me")
                .header("traceparent", "no-es-un-traceparent").build());

        assertThat(mutated.getRequest().getHeaders().getFirst("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    @Test
    void a_traceparent_with_a_future_version_is_REGENERATED() {
        // Only version 00 is parsed downstream (traceId/spanId by position).
        // Accepting an unknown version preserves a trail we cannot read.
        var mutated = run(MockServerHttpRequest.get("/api/users/me")
                .header("traceparent", "cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").build());

        assertThat(mutated.getRequest().getHeaders().getFirst("traceparent"))
                .startsWith("00-");
    }

    @Test
    void publishes_the_traceId_and_spanId_of_the_traceparent_in_the_context() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        ContextView ctx = runAndCaptureContext(MockServerHttpRequest.get("/api/users/me")
                .header("traceparent", traceparent).build());

        assertThat(ctx.<String>get(CorrelationIdFilter.CTX_TRACE_ID))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(ctx.<String>get(CorrelationIdFilter.CTX_SPAN_ID))
                .isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void without_a_traceparent_it_generates_valid_traceId_and_spanId_in_the_context() {
        ContextView ctx = runAndCaptureContext(MockServerHttpRequest.get("/api/users/me").build());

        assertThat(ctx.<String>get(CorrelationIdFilter.CTX_TRACE_ID)).matches("[0-9a-f]{32}");
        assertThat(ctx.<String>get(CorrelationIdFilter.CTX_SPAN_ID)).matches("[0-9a-f]{16}");
    }

    @Test
    void generates_a_traceparent_when_missing() {
        var mutated = run(MockServerHttpRequest.get("/api/users/me").build());
        assertThat(mutated.getRequest().getHeaders().getFirst("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    @Test
    void the_X_Request_Id_also_goes_out_in_the_RESPONSE() {
        // Without this, a user reporting an error has no id to hand over.
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();
        assertThat(ex.getResponse().getHeaders().getFirst(IdentityHeaders.REQUEST_ID)).isNotBlank();
    }

    @Test
    void it_is_the_first_filter() {
        // The @Order values go in steps of 10 on purpose: it leaves room to
        // slot in PipelineOrderIT's spy filters (task 13) without touching
        // the relative order of the real ones.
        assertThat(filter.getOrder()).isEqualTo(10);
    }
}
