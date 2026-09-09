package ar.edu.utn.frc.tup.p4.apigateway.filters;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defence in depth: if a configuration bug let something private through
 * unauthenticated, this filter cuts it BEFORE IdentityPropagationFilter injects
 * empty headers the destination would trust blindly.
 */
class PrivateRouteGuardTest {

    private final PrivateRouteGuard guard = new PrivateRouteGuard();

    private Jwt.Builder jwt() {
        return Jwt.withTokenValue("x").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("s");
    }

    private HttpStatus run(Jwt token) {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        Mono<Void> resultado = guard.filter(ex, e -> Mono.empty());
        if (token != null) {
            // Two-arg constructor: it is the ONLY one that leaves the token
            // authenticated, and it is what JwtReactiveAuthenticationManager
            // puts in the context in production. With the one-arg one the guard
            // rejects everything and the test would be checking the wrong thing.
            resultado = resultado.contextWrite(ReactiveSecurityContextHolder
                    .withAuthentication(new JwtAuthenticationToken(token, List.of())));
        }
        StepVerifier.create(resultado).verifyComplete();
        return (HttpStatus) ex.getResponse().getStatusCode();
    }

    @Test
    void sin_Authentication_en_una_ruta_privada_corta_con_401() {
        assertThat(run(null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void un_type_desconocido_corta_con_401() {
        assertThat(run(jwt().claim("type", "robot").claim("roles", List.of("X")).build()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void type_user_con_roles_VACIOS_corta_con_401() {
        // If it happened, X-User-Roles would go out empty and the destination
        // would have a principal with no role: neither allowed nor denied.
        assertThat(run(jwt().claim("type", "user").claim("roles", List.of()).build()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void type_service_SIN_el_rol_MS_corta_con_401() {
        assertThat(run(jwt().claim("type", "service").claim("roles", List.of("STUDENT")).build()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void un_token_bien_formado_pasa() {
        assertThat(run(jwt().claim("type", "user").claim("roles", List.of("STUDENT"))
                .claim("sid", "s").claim("est", "ACTIVE").claim("pwd", false)
                .claim("onb", false).build())).isNull();   // no status written: it continued the chain
    }

    @Test
    void un_rol_de_ADMIN_no_recibe_trato_distinto_que_uno_de_ALUMNO() {
        // R3 - DoD criterion #11: it checks the SHAPE of the token, never the
        // role against the route. Both pass; the permission decision belongs to
        // the destination's @PreAuthorize, not here.
        Jwt admin = jwt().claim("type", "user").claim("roles", List.of("ADMIN"))
                .claim("sid", "s").claim("est", "ACTIVE").claim("pwd", false)
                .claim("onb", false).build();
        Jwt alumno = jwt().claim("type", "user").claim("roles", List.of("STUDENT"))
                .claim("sid", "s").claim("est", "ACTIVE").claim("pwd", false)
                .claim("onb", false).build();

        assertThat(run(admin)).isEqualTo(run(alumno)).isNull();
    }
}
