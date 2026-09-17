package ar.edu.utn.frc.tup.p4.apigateway.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterMicroTraceFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReactiveListOperations<String, String> list = mock(ReactiveListOperations.class);
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final InterMicroTraceFilter filter = new InterMicroTraceFilter(redis, mapper);

    private Route routeTo(String service) {
        return Route.async()
                .id(service)
                .uri(URI.create("lb://" + service))
                .order(0)
                .predicate(p -> true)
                .build();
    }

    @Test
    void records_the_call_with_destination_origin_status_and_ms() throws Exception {
        when(redis.opsForList()).thenReturn(list);
        when(list.leftPush(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(list.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/echo/cliente/perfil/42").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, routeTo("echo-service"));

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        verify(list, timeout(2000)).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), anyString());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(list).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), json.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = mapper.readValue(json.getValue(), Map.class);
        assertThat(entry).containsEntry("destino", "echo-service")
                .containsEntry("origen", "ANON")
                .containsEntry("metodo", "GET")
                .containsEntry("path", "/api/echo/cliente/perfil/42")
                // In the mock the response is not written, so the status the
                // filter sees is null -> 0. At runtime it is the real status.
                .containsEntry("status", 0)
                .containsKey("ms").containsKey("traceId").containsKey("requestId");
    }

    @Test
    void distinguishes_the_person_origin_from_the_service_origin() throws Exception {
        when(redis.opsForList()).thenReturn(list);
        when(list.leftPush(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(list.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        Jwt service = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("type", "service")
                .claim("sub", "echo-service")
                .build();

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/profile/42").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, routeTo("users-service"));
        ex.getAttributes().put(PrivateRouteGuard.ATTR_JWT, service);

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(list, timeout(2000)).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), json.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = mapper.readValue(json.getValue(), Map.class);
        assertThat(entry).containsEntry("origen", "MS")
                .containsEntry("actor", "echo-service")
                .containsEntry("destino", "users-service");
    }

    @Test
    void without_a_resolved_route_it_does_not_record() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        verifyNoInteractions(redis);
    }

    @Test
    void a_down_redis_does_not_take_down_the_request() {
        when(redis.opsForList()).thenReturn(list);
        when(list.leftPush(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException("redis down")));
        when(list.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, routeTo("users-service"));

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();
    }

    @Test
    void NEVER_stores_the_Authorization_header() throws Exception {
        when(redis.opsForList()).thenReturn(list);
        when(list.leftPush(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(list.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me")
                .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.SECRET.firma").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, routeTo("users-service"));

        StepVerifier.create(filter.filter(ex, e -> Mono.empty())).verifyComplete();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(list, timeout(2000)).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), json.capture());

        assertThat(json.getValue())
                .doesNotContain("Bearer").doesNotContain("SECRET").doesNotContain("eyJ");
    }

    @Test
    void runs_after_identity_propagation() {
        assertThat(filter.getOrder()).isGreaterThan(70).isLessThan(80);
    }
}
