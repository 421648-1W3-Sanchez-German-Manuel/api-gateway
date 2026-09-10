package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

class PublicPrivateRouteIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    /** The private routes go through SessionGuard: the sid has to be in Redis. */
    private String tokenDe(UUID u) {
        seedSession(redis, u, "sid-1");
        return TokenFactory.persona(u, "sid-1");
    }

    @Test
    void una_ruta_public_pasa_SIN_token() {
        cliente.post().uri("/api/users/public/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void el_mismo_micro_fuera_de_public_SIN_token_da_401() {
        cliente.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void el_JWKS_pasa_sin_token_y_LLEGA_AL_DESTINO() {
        // DEC-27 - the bug this route had: it passed Security and the three
        // guards and then died on a 404 from the gateway itself, because the
        // dynamic locator generates Path=/api/{name}/**. It needs a static route.
        cliente.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk();
    }

    @Test
    void una_ruta_privada_con_token_valido_pasa() {
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenDe(UUID.randomUUID()))
                .exchange().expectStatus().isOk();
    }
}
