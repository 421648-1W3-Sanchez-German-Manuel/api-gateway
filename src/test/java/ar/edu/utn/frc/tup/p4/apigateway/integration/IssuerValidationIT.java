package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * DEC-44 - validated ALWAYS, with no flag. The safety net is
 * TokenContractTest on the users-service side, plus a 401 that says WHICH claim
 * was missing - that turns "everything returns 401 and I do not know why" into
 * de grep.
 */
class IssuerValidationIT extends AbstractGatewayTest {

    @Test
    void un_token_con_iss_distinto_es_rechazado() {
        String malo = TokenFactory.persona(UUID.randomUUID(), "sid-1",
                b -> b.issuer("otro-emisor"));
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + malo)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void un_token_SIN_iss_es_rechazado() {
        // This is exactly the token an attacker would forge: that is why there
        // validador tolerante que acepte "ausente o correcto".
        String sinIss = TokenFactory.persona(UUID.randomUUID(), "sid-1", b -> b.issuer(null));
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + sinIss)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void un_token_con_algoritmo_distinto_de_RS256_es_rechazado() {
        // "none" included. jws-algorithms: RS256 in the yml covers it.
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.hs256Falso())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void el_cuerpo_del_401_NO_dice_que_claim_falto() {
        // An attacker is not told what the token was missing: that goes to the log.
        String malo = TokenFactory.persona(UUID.randomUUID(), "s", b -> b.issuer("otro"));
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + malo)
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").value(d ->
                        org.assertj.core.api.Assertions.assertThat((String) d)
                                .doesNotContain("iss"));
    }
}
