package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

class PublicPrivateRouteIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    /** The private routes go through SessionGuard: the sid has to be in Redis. */
    private String tokenFor(UUID u) {
        seedSession(redis, u, "sid-1");
        return TokenFactory.person(u, "sid-1");
    }

    @Test
    void a_public_route_passes_WITHOUT_a_token() {
        client.post().uri("/api/users/public/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void the_same_service_outside_public_WITHOUT_a_token_gives_401() {
        client.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void the_JWKS_passes_without_a_token_and_REACHES_THE_DESTINATION() {
        // DEC-27 - the bug this route had: it passed Security and the three
        // guards and then died on a 404 from the gateway itself, because the
        // dynamic locator generates Path=/api/{name}/**. It needs a static route.
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk();
    }

    @Test
    void a_private_route_with_a_valid_token_passes() {
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenFor(UUID.randomUUID()))
                .exchange().expectStatus().isOk();
    }
}
