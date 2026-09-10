package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.filters.PrivateRouteGuard;
import ar.edu.utn.frc.tup.p4.apigateway.support.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PipelineOrderIT.FilterSpies.class)
class PipelineOrderIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @BeforeEach
    void clear() { FilterSequence.clear(); }

    private void anAuthenticatedRequest() {
        UUID u = UUID.randomUUID();
        seedSession(redis, u, "sid-1");
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-1"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void Spring_Security_runs_BEFORE_ALL_GlobalFilters() {
        // The premise of the whole design: if Security ran later, the guards
        // would have no validated Jwt to hang off.
        anAuthenticatedRequest();

        List<String> steps = FilterSequence.steps();
        assertThat(steps.indexOf("security")).isLessThan(steps.indexOf("order-1"));
    }

    @Test
    void the_eight_GlobalFilters_run_in_declared_order() {
        anAuthenticatedRequest();

        assertThat(FilterSequence.steps())
                .filteredOn(p -> p.startsWith("order-"))
                .containsExactly("order-1", "order-2", "order-3", "order-4",
                                 "order-5", "order-6", "order-7", "order-8");
    }

    @Test
    void the_GATEWAY_ROUTE_ATTR_is_already_populated_at_order_6() {
        // THE check the spec asks for. ServiceAudienceFilter (@Order 6) cannot
        // compare aud against the destination if the attribute is not there.
        //
        // It SHOULD be: RoutePredicateHandlerMapping sets it when resolving the
        // handler, BEFORE the GlobalFilter chain starts. It is NOT set by
        // RouteToRequestUrlFilter (@Order 10000), which CONSUMES it to build the
        // destination URL. But that is a Spring Cloud Gateway internal, not a
        // contract guarantee: hence the check.
        anAuthenticatedRequest();
        assertThat(FilterSequence.steps()).contains("route-resolved-at-order-6");
    }

    @Test
    void ServiceAudienceFilter_runs_BEFORE_IdentityPropagationFilter() {
        // A non-negotiable invariant. The other way round, the destination would
        // receive identity headers injected from a token whose `aud` had not
        // been validated yet: anti-spoofing breaks.
        anAuthenticatedRequest();

        List<String> steps = FilterSequence.steps();
        assertThat(steps.indexOf("order-6")).isLessThan(steps.indexOf("order-7"));
    }

    @TestConfiguration
    static class FilterSpies {

        private static GlobalFilter spy(int order) {
            return new GlobalFilter() {
                @Override public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
                    FilterSequence.register("order-" + order);
                    if (order == 6 && ex.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) != null) {
                        FilterSequence.register("route-resolved-at-order-6");
                    }
                    if (order == 1 && ex.getAttribute(PrivateRouteGuard.ATTR_JWT) == null) {
                        // Security already ran even if the attribute is not there
                        // yet: the @Order(4) guard sets it. See the other spy.
                        FilterSequence.register("jwt-not-yet-in-attribute");
                    }
                    return chain.filter(ex);
                }
                // The spies run IMMEDIATELY before the real filter of that
                // order, subtracting 1 from the base weight.
                public int getOrder() { return order * 10 - 1; }
            };
        }

        @Bean GlobalFilter spy1() { return spy(1); }
        @Bean GlobalFilter spy2() { return spy(2); }
        @Bean GlobalFilter spy3() { return spy(3); }
        @Bean GlobalFilter spy4() { return spy(4); }
        @Bean GlobalFilter spy5() { return spy(5); }
        @Bean GlobalFilter spy6() { return spy(6); }
        @Bean GlobalFilter spy7() { return spy(7); }
        @Bean GlobalFilter spy8() { return spy(8); }

        /** A Security `WebFilter` records that the reactive chain already ran. */
        @Bean
        org.springframework.web.server.WebFilter securitySpy() {
            return (exchange, chain) -> chain.filter(exchange)
                    .doFirst(() -> FilterSequence.register("security"));
        }
    }
}
