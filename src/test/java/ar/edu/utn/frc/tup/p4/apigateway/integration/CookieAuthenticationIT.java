package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Instant;
import java.util.Date;
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

    /**
     * A PUBLIC route must work even when the browser attaches a fu_at the
     * decoder cannot accept.
     *
     * SessionGuard already skips public routes for this exact reason, but that
     * only covers the SESSION layer. The decoder runs EARLIER: the
     * AuthenticationWebFilter that oauth2ResourceServer installs is not aware
     * of permitAll, so a cookie that fails signature or exp used to cut the
     * request with a 401 before the public route ran at all.
     *
     * The trap that closes: fu_at has Path=/, so it travels to /refresh AND to
     * /login. The frontend reacts to not-authenticated by clearing the session
     * and navigating to /login -- which receives the same cookie and answers
     * 401 again. The person cannot get out without deleting cookies by hand.
     * It fires on key rotation and on a browser clock more than 60 s behind.
     */
    @Test
    void a_public_route_works_with_an_EXPIRED_cookie() {
        String expired = TokenFactory.person(UUID.randomUUID(), "sid-expired",
                b -> b.expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                      .issueTime(Date.from(Instant.now().minusSeconds(7200))));

        client.post().uri("/api/users/public/auth/refresh")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, expired)
                .exchange().expectStatus().isOk();
    }

    /** Same case, with a value that is not even a JWT: a truncated cookie. */
    @Test
    void a_public_route_works_with_a_GARBAGE_cookie() {
        client.post().uri("/api/users/public/auth/refresh")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, "not-a-jwt")
                .exchange().expectStatus().isOk();
    }

    /**
     * The other half of the same change: skipping the converter on a public
     * route must NOT leak into the private ones, where an invalid cookie has to
     * keep answering 401.
     */
    @Test
    void a_PRIVATE_route_still_rejects_an_EXPIRED_cookie() {
        String expired = TokenFactory.person(UUID.randomUUID(), "sid-expired",
                b -> b.expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                      .issueTime(Date.from(Instant.now().minusSeconds(7200))));

        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, expired)
                .exchange().expectStatus().isUnauthorized();
    }
}
