package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
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

    private String tokenDe(UUID u) {
        seedSession(redis, u, "sid-1");
        return TokenFactory.persona(u, "sid-1");
    }

    @Test
    void un_header_de_identidad_FALSIFICADO_es_reemplazado_por_el_del_token()
            throws InterruptedException {
        // DoD criterion #10. THE gateway security test: if this one fails,
        // anyone is ADMIN by sending a header.
        UUID real = UUID.randomUUID();
        UUID atacante = UUID.randomUUID();

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenDe(real))
                .header(IdentityHeaders.USER_ID, atacante.toString())
                .header(IdentityHeaders.USER_ROLES, "ADMIN")
                .header(IdentityHeaders.PRINCIPAL_TYPE, "service")
                .exchange().expectStatus().isOk();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getHeader(IdentityHeaders.USER_ID)).isEqualTo(real.toString());
        assertThat(recibido.getHeader(IdentityHeaders.USER_ROLES)).isEqualTo("STUDENT");
        assertThat(recibido.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("user");
    }

    @Test
    void en_una_ruta_PUBLICA_los_headers_entrantes_se_BORRAN_y_no_se_inyecta_ninguno()
            throws InterruptedException {
        // Seeing no X-Principal-Type, the destination knows it is public traffic.
        // If the stripping did not apply on public routes it would be the
        // biggest hole of all: a tokenless route where you declare yourself ADMIN.
        cliente.post().uri("/api/users/public/auth/login")
                .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                .header(IdentityHeaders.USER_ROLES, "ADMIN")
                .exchange().expectStatus().isOk();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getHeader(IdentityHeaders.USER_ID)).isNull();
        assertThat(recibido.getHeader(IdentityHeaders.USER_ROLES)).isNull();
        assertThat(recibido.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isNull();
    }

    @Test
    void un_token_de_servicio_inyecta_X_Service_Id_y_X_Service_Scopes()
            throws InterruptedException {
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("service");
        assertThat(recibido.getHeader(IdentityHeaders.SERVICE_ID)).isEqualTo("cursos-service");
        // DEC-05: MS first, comma with no space.
        assertThat(recibido.getHeader(IdentityHeaders.SERVICE_SCOPES))
                .isEqualTo("MS,users.profile.read");
        // A service token carries no person headers.
        assertThat(recibido.getHeader(IdentityHeaders.USER_ID)).isNull();
    }

    @Test
    void el_Authorization_original_se_REENVIA_al_destino() throws InterruptedException {
        // DEC-03: it enables internal zero-trust. It does not relax anti-spoofing:
        // the token is signed, the X-* headers are not.
        String token = tokenDe(UUID.randomUUID());
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        assertThat(ultimoRequestAlDestino().getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void el_traceparent_llega_al_destino() throws InterruptedException {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenDe(UUID.randomUUID()))
                .header("traceparent", traceparent)
                .exchange().expectStatus().isOk();

        assertThat(ultimoRequestAlDestino().getHeader("traceparent")).isEqualTo(traceparent);
    }
}
