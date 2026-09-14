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
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenDe(real))
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

    /**
     * DEC-03 sigue vigente, pero solo para el canal que todavia usa header:
     * un token de servicio. Antes de la spec "Sesion en Cookies" esto se
     * probaba con un token de persona - ya no aplica, porque un token de
     * persona por header es justo lo que decision 3 rechaza (ver el test de
     * mas abajo). Con la cookie fu_at no hay Authorization entrante que
     * reenviar: no hay nada que verificar ahi, por eso no hay un test que
     * afirme "llega vacio" - afirmar la ausencia de un header no es una
     * propiedad interesante del sistema.
     */
    @Test
    void el_Authorization_original_de_un_token_de_SERVICIO_se_REENVIA_al_destino()
            throws InterruptedException {
        // DEC-03: it enables internal zero-trust. It does not relax anti-spoofing:
        // the token is signed, the X-* headers are not.
        String token = TokenFactory.servicio("cursos-service", "users-service", "users.profile.read");
        cliente.get().uri("/api/users/profile/x").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        assertThat(ultimoRequestAlDestino().getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    /**
     * Decision 3 del spec "Sesion en Cookies" - estado final del cutover:
     * un token de persona puesto a mano en el header ya no sirve, ni
     * siquiera si es perfectamente valido. Es el cierre del loophole que
     * dejaba la migracion a mitad de camino: un token de persona robado
     * (XSS, log, lo que sea) deja de ser usable por header.
     */
    @Test
    void un_token_de_PERSONA_por_header_es_rechazado_aunque_sea_valido() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenDe(u))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void el_traceparent_llega_al_destino() throws InterruptedException {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        cliente.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenDe(UUID.randomUUID()))
                .header("traceparent", traceparent)
                .exchange().expectStatus().isOk();

        assertThat(ultimoRequestAlDestino().getHeader("traceparent")).isEqualTo(traceparent);
    }
}
