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
    private final ReactiveListOperations<String, String> lista = mock(ReactiveListOperations.class);
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final InterMicroTraceFilter filtro = new InterMicroTraceFilter(redis, mapper);

    private Route rutaHacia(String servicio) {
        return Route.async()
                .id(servicio)
                .uri(URI.create("lb://" + servicio))
                .order(0)
                .predicate(p -> true)
                .build();
    }

    @Test
    void registra_la_llamada_con_destino_origen_status_y_ms() throws Exception {
        when(redis.opsForList()).thenReturn(lista);
        when(lista.leftPush(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(lista.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/echo/cliente/perfil/42").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, rutaHacia("echo-service"));

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        verify(lista, timeout(2000)).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), anyString());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(lista).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), json.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> entrada = mapper.readValue(json.getValue(), Map.class);
        assertThat(entrada).containsEntry("destino", "echo-service")
                .containsEntry("origen", "ANON")
                .containsEntry("metodo", "GET")
                .containsEntry("path", "/api/echo/cliente/perfil/42")
                // En el mock la respuesta no se escribe, asi que el status que
                // ve el filtro es null -> 0. En runtime es el status real.
                .containsEntry("status", 0)
                .containsKey("ms").containsKey("traceId").containsKey("requestId");
    }

    @Test
    void distingue_el_origen_persona_del_origen_servicio() throws Exception {
        when(redis.opsForList()).thenReturn(lista);
        when(lista.leftPush(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(lista.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        Jwt servicio = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("type", "service")
                .claim("sub", "echo-service")
                .build();

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/profile/42").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, rutaHacia("users-service"));
        ex.getAttributes().put(PrivateRouteGuard.ATTR_JWT, servicio);

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(lista, timeout(2000)).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), json.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> entrada = mapper.readValue(json.getValue(), Map.class);
        assertThat(entrada).containsEntry("origen", "MS")
                .containsEntry("actor", "echo-service")
                .containsEntry("destino", "users-service");
    }

    @Test
    void sin_ruta_resuelta_no_registra() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        verifyNoInteractions(redis);
    }

    @Test
    void un_redis_caido_no_tumba_el_request() {
        when(redis.opsForList()).thenReturn(lista);
        when(lista.leftPush(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException("redis abajo")));
        when(lista.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, rutaHacia("users-service"));

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();
    }

    @Test
    void NUNCA_almacena_el_header_Authorization() throws Exception {
        when(redis.opsForList()).thenReturn(lista);
        when(lista.leftPush(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(lista.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.empty());

        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me")
                .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.SECRETO.firma").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, rutaHacia("users-service"));

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(lista, timeout(2000)).leftPush(eq(InterMicroTraceFilter.REDIS_KEY), json.capture());

        assertThat(json.getValue())
                .doesNotContain("Bearer").doesNotContain("SECRETO").doesNotContain("eyJ");
    }

    @Test
    void corre_despues_de_la_propagacion_de_identidad() {
        assertThat(filtro.getOrder()).isGreaterThan(70).isLessThan(80);
    }
}