package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec "Sesion en Cookies" S02.2/S02.3: the person JWT can now arrive via the
 * fu_at cookie in addition to the Authorization header. The cookie is a
 * FALLBACK in the EXTRACTION, not a second channel on equal footing - which is
 * why, with both present, the converter always tries the header first.
 *
 * Decision 3 (final state of the cutover) goes further than extraction: a
 * person token (type=user) is only VALID if it arrived via cookie, a service
 * one (type=service) only if it arrived via header. See the proof of the
 * latter in IdentityPropagationIT (a_PERSON_token_via_header...).
 */
class CookieAuthenticationIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String tokenFor(UUID u, String sid) {
        seedSession(redis, u, sid);
        return TokenFactory.person(u, sid);
    }

    @Test
    void a_private_route_with_a_JWT_in_the_cookie_passes() {
        String jwt = tokenFor(UUID.randomUUID(), "sid-cookie");
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, jwt)
                .exchange().expectStatus().isOk();
    }

    @Test
    void with_NEITHER_header_NOR_cookie_it_still_gives_401() {
        client.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void an_invalid_JWT_cookie_gives_401_just_like_an_invalid_header() {
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, "this-is-not-a-jwt")
                .exchange().expectStatus().isUnauthorized();
    }

    /**
     * Explicit precedence IN THE EXTRACTION: the header always wins. It is
     * tested with a SERVICE token in the header (not a person one): with
     * decision 3 already active, a person token via header is rejected no
     * matter which cookie comes along, so that combination is useless for
     * testing precedence — it would test the rejection, not the channel choice.
     */
    @Test
    void with_a_SERVICE_header_and_a_PERSON_cookie_present_the_header_wins() throws InterruptedException {
        UUID cookieUser = UUID.randomUUID();
        String headerToken = TokenFactory.service("cursos-service", "users-service", "users.profile.read");
        String cookieToken = tokenFor(cookieUser, "sid-cookie-2");

        client.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + headerToken)
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, cookieToken)
                .exchange().expectStatus().isOk();

        var received = lastRequestToDestination();
        assertThat(received.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("service");
        assertThat(received.getHeader(IdentityHeaders.SERVICE_ID)).isEqualTo("cursos-service");
    }
}
