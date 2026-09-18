package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropagationIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String tokenFor(UUID u) {
        seedSession(redis, u, "sid-1");
        return TokenFactory.person(u, "sid-1");
    }

    @Test
    void a_FORGED_identity_header_is_replaced_by_the_token_one()
            throws InterruptedException {
        // DoD criterion #10. THE gateway security test: if this one fails,
        // anyone is ADMIN by sending a header.
        UUID real = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenFor(real))
                .header(IdentityHeaders.USER_ID, attacker.toString())
                .header(IdentityHeaders.USER_ROLES, "ADMIN")
                .header(IdentityHeaders.PRINCIPAL_TYPE, "service")
                .exchange().expectStatus().isOk();

        RecordedRequest received = lastRequestToDestination();
        assertThat(received.getHeader(IdentityHeaders.USER_ID)).isEqualTo(real.toString());
        assertThat(received.getHeader(IdentityHeaders.USER_ROLES)).isEqualTo("STUDENT");
        assertThat(received.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("user");
    }

    @Test
    void on_a_PUBLIC_route_the_incoming_headers_are_STRIPPED_and_none_is_injected()
            throws InterruptedException {
        // Seeing no X-Principal-Type, the destination knows it is public traffic.
        // If the stripping did not apply on public routes it would be the
        // biggest hole of all: a tokenless route where you declare yourself ADMIN.
        client.post().uri("/api/users/public/auth/login")
                .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                .header(IdentityHeaders.USER_ROLES, "ADMIN")
                .exchange().expectStatus().isOk();

        RecordedRequest received = lastRequestToDestination();
        assertThat(received.getHeader(IdentityHeaders.USER_ID)).isNull();
        assertThat(received.getHeader(IdentityHeaders.USER_ROLES)).isNull();
        assertThat(received.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isNull();
    }

    @Test
    void a_service_token_injects_X_Service_Id_and_X_Service_Scopes()
            throws InterruptedException {
        client.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.service(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();

        RecordedRequest received = lastRequestToDestination();
        assertThat(received.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("service");
        assertThat(received.getHeader(IdentityHeaders.SERVICE_ID)).isEqualTo("cursos-service");
        // DEC-05: MS first, comma with no space.
        assertThat(received.getHeader(IdentityHeaders.SERVICE_SCOPES))
                .isEqualTo("MS,users.profile.read");
        // A service token carries no person headers.
        assertThat(received.getHeader(IdentityHeaders.USER_ID)).isNull();
    }

    /**
     * DEC-03 is still in force, but only for the channel that still uses a
     * header: a service token. Before the "Sesion en Cookies" spec this was
     * tested with a person token - it no longer applies, because a person token
     * via header is exactly what decision 3 rejects (see the test below). With
     * the fu_at cookie there is no incoming Authorization to forward: there is
     * nothing to verify there, which is why there is no test asserting "it
     * arrives empty" - asserting the absence of a header is not an interesting
     * property of the system.
     */
    @Test
    void the_original_Authorization_of_a_SERVICE_token_is_FORWARDED_to_the_destination()
            throws InterruptedException {
        // DEC-03: it enables internal zero-trust. It does not relax anti-spoofing:
        // the token is signed, the X-* headers are not.
        String token = TokenFactory.service("cursos-service", "users-service", "users.profile.read");
        client.get().uri("/api/users/profile/x").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        assertThat(lastRequestToDestination().getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    /**
     * Decision 3 of the "Sesion en Cookies" spec - final state of the cutover:
     * a person token put by hand into the header no longer works, not even if
     * it is perfectly valid. It is the closing of the loophole the migration
     * left half-way: a stolen person token (XSS, log, whatever) stops being
     * usable via header.
     */
    @Test
    void a_PERSON_token_via_header_is_rejected_even_if_valid() {
        UUID u = UUID.randomUUID();
        client.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenFor(u))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void the_traceparent_reaches_the_destination() throws InterruptedException {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenFor(UUID.randomUUID()))
                .header("traceparent", traceparent)
                .exchange().expectStatus().isOk();

        assertThat(lastRequestToDestination().getHeader("traceparent")).isEqualTo(traceparent);
    }
}
