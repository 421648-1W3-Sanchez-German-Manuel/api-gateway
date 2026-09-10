package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filtro = new CorrelationIdFilter();

    private ServerWebExchange run(MockServerHttpRequest req) {
        var ex = MockServerWebExchange.from(req);
        AtomicReference<ServerWebExchange> visto = new AtomicReference<>();
        GatewayFilterChain chain = e -> { visto.set(e); return Mono.empty(); };
        StepVerifier.create(filtro.filter(ex, chain)).verifyComplete();
        return visto.get();
    }

    @Test
    void genera_un_X_Request_Id_si_no_viene() {
        var mutado = run(MockServerHttpRequest.get("/api/users/me").build());
        assertThat(mutado.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID))
                .isNotBlank();
    }

    @Test
    void CONSERVA_el_traceparent_entrante() {
        // W3C Trace Context: if another service already started the trace, we
        // do not overwrite it - that splits the trail in two right at the edge.
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var mutado = run(MockServerHttpRequest.get("/api/users/me")
                .header("traceparent", traceparent).build());

        assertThat(mutado.getRequest().getHeaders().getFirst("traceparent")).isEqualTo(traceparent);
    }

    @Test
    void genera_un_traceparent_si_no_viene() {
        var mutado = run(MockServerHttpRequest.get("/api/users/me").build());
        assertThat(mutado.getRequest().getHeaders().getFirst("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    @Test
    void el_X_Request_Id_tambien_sale_en_la_RESPUESTA() {
        // Without this, a user reporting an error has no id to hand over.
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();
        assertThat(ex.getResponse().getHeaders().getFirst(IdentityHeaders.REQUEST_ID)).isNotBlank();
    }

    @Test
    void es_el_primer_filtro() {
        // The @Order values go in steps of 10 on purpose: it leaves room to
        // slot in PipelineOrderIT's spy filters (task 13) without touching
        // the relative order of the real ones.
        assertThat(filtro.getOrder()).isEqualTo(10);
    }
}
