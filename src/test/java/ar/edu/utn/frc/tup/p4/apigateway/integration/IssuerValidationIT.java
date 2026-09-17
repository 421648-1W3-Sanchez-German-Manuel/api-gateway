package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.security.CookieOrHeaderBearerConverter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * DEC-44 - validated ALWAYS, with no flag. The safety net is
 * TokenContractTest on the users-service side, plus a 401 that says WHICH claim
 * was missing - that turns "everything returns 401 and I do not know why" into
 * a grep.
 */
class IssuerValidationIT extends AbstractGatewayTest {

    @Test
    void a_token_with_a_different_iss_is_rejected() {
        String bad = TokenFactory.person(UUID.randomUUID(), "sid-1",
                b -> b.issuer("another-issuer"));
        client.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, bad)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void a_token_WITHOUT_iss_is_rejected() {
        // This is exactly the token an attacker would forge: that is why there
        // is no tolerant validator that accepts "absent or correct".
        String noIss = TokenFactory.person(UUID.randomUUID(), "sid-1", b -> b.issuer(null));
        client.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, noIss)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void a_token_with_an_algorithm_other_than_RS256_is_rejected() {
        // "none" included. jws-algorithms: RS256 in the yml covers it.
        client.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, TokenFactory.hs256Forgery())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void the_401_body_does_NOT_say_which_claim_was_missing() {
        // An attacker is not told what the token was missing: that goes to the log.
        String bad = TokenFactory.person(UUID.randomUUID(), "s", b -> b.issuer("another"));
        client.get().uri("/api/users/me").cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, bad)
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").value(d ->
                        org.assertj.core.api.Assertions.assertThat((String) d)
                                .doesNotContain("iss"));
    }
}
