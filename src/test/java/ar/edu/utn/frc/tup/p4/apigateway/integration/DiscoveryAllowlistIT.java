package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.config.DiscoveryLocatorConfig;
import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3 - dynamic routing governed by the typed allowlist. It covers:
 *  - R7 · the path is NOT rewritten (DoD #2);
 *  - DoD #3 · a service OUTSIDE the allowlist is not reachable;
 *  - the serviceId &lt;-&gt; path segment derivation the spec sets;
 *  - the startup validation that rejects the allowlist if it includes infra.
 *
 * <p>Without {@code AllowlistRouteLocator} the routes were generated via the
 * DiscoveryClient locator's {@code include-expression}, and the SpEL
 * {@code serviceId.toLowerCase()} blew up in the refresh listener, NOT at
 * startup: the Gateway came up healthy, with an empty table, and answered 404
 * to everything. These tests document the contract the new route generation
 * must fulfil.
 */
class DiscoveryAllowlistIT extends AbstractGatewayTest {

    @Autowired
    ReactiveStringRedisTemplate redis;

    @Test
    void the_path_reaches_the_DESTINATION_WITHOUT_BEING_REWRITTEN() throws Exception {
        // R7 - DoD #2. The destination receives /api/users/me, not /me.
        // If someone adds a RewritePath "to clean up the prefix", this test
        // catches it: the destination controllers are mapped WITH the prefix.
        UUID sub = UUID.randomUUID();
        // The session has to be seeded, and with the SAME sid as the token:
        // SessionGuard runs as a WebFilter BEFORE routing, so a person token
        // without a live session is cut with 401 and the destination receives
        // nothing. Without this the assert below waits for a request that never
        // arrives.
        seedSession(redis, sub, "sid-1");
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.person(sub, "sid-1"))
                .exchange();

        RecordedRequest received = lastRequestToDestination();
        assertThat(received.getPath()).isEqualTo("/api/users/me");
    }

    @Test
    void a_service_OUTSIDE_the_allowlist_answers_404() {
        // DoD #3. Registering in Eureka does NOT expose a service: until it is
        // in the typed allowlist, it does not exist to the outside.
        // Same reason: without a seeded session this would give 401 and not
        // 404, and the test would end up testing the session guard instead of
        // the allowlist.
        UUID sub = UUID.randomUUID();
        seedSession(redis, sub, "s");
        client.get().uri("/api/otro/lo-que-sea")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.person(sub, "s"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void the_serviceId_to_segment_derivation_is_the_one_from_the_spec() {
        var props = new GatewayRoutingProperties(List.of("users-service"), "-service", "/api");
        assertThat(props.serviceIdToPathSegment("users-service")).isEqualTo("users");
        assertThat(props.serviceIdToPathSegment("USERS-SERVICE")).isEqualTo("users");
        assertThat(props.serviceIdToPathSegment("cursos-service")).isEqualTo("cursos");
    }

    @Test
    void startup_FAILS_if_the_allowlist_includes_the_gateway_itself() {
        // Routing to itself produces an infinite loop that shows up as a stack
        // overflow or a timeout, never as a readable error.
        var props = new GatewayRoutingProperties(
                List.of("users-service", "api-gateway"), "-service", "/api");
        assertThatThrownBy(() -> new DiscoveryLocatorConfig(props).validateAllowlist())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-gateway");
    }

    @Test
    void startup_FAILS_if_the_allowlist_includes_eureka() {
        var props = new GatewayRoutingProperties(
                List.of("users-service", "eureka-server"), "-service", "/api");
        assertThatThrownBy(() -> new DiscoveryLocatorConfig(props).validateAllowlist())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eureka-server");
    }
}
