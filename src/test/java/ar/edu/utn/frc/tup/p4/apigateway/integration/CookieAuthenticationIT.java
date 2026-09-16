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
 * Spec "Sesion en Cookies" S02.2/S02.3: el JWT de persona ahora puede llegar
 * por la cookie fu_at ademas del header Authorization. La cookie es un
 * FALLBACK en la EXTRACCION, no un segundo canal en pie de igualdad -por
 * eso, con las dos presentes, el converter siempre prueba el header primero.
 *
 * Decision 3 (estado final del cutover) va mas alla que la extraccion: un
 * token de persona (type=user) solo es VALIDO si llego por cookie, uno de
 * servicio (type=service) solo si llego por header. Ver la prueba de esto
 * ultimo en IdentityPropagationIT (un_token_de_PERSONA_por_header...).
 */
class CookieAuthenticationIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String tokenDe(UUID u, String sid) {
        seedSession(redis, u, sid);
        return TokenFactory.persona(u, sid);
    }

    @Test
    void una_ruta_privada_con_JWT_en_cookie_pasa() {
        String jwt = tokenDe(UUID.randomUUID(), "sid-cookie");
        cliente.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, jwt)
                .exchange().expectStatus().isOk();
    }

    @Test
    void sin_header_NI_cookie_sigue_dando_401() {
        cliente.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void una_cookie_con_JWT_invalido_da_401_igual_que_un_header_invalido() {
        cliente.get().uri("/api/users/me")
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, "esto-no-es-un-jwt")
                .exchange().expectStatus().isUnauthorized();
    }

    /**
     * Precedencia explicita EN LA EXTRACCION: el header siempre gana. Se
     * prueba con un token de SERVICIO en el header (no de persona): con la
     * decision 3 ya activa, un token de persona por header se rechaza sin
     * importar que cookie traiga al lado, asi que esa combinacion no sirve
     * para probar precedencia — probaria el rechazo, no la eleccion de canal.
     */
    @Test
    void con_header_de_SERVICIO_y_cookie_de_PERSONA_presentes_gana_el_header() throws InterruptedException {
        UUID uCookie = UUID.randomUUID();
        String tokenHeader = TokenFactory.servicio("cursos-service", "users-service", "users.profile.read");
        String tokenCookie = tokenDe(uCookie, "sid-cookie-2");

        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + tokenHeader)
                .cookie(CookieOrHeaderBearerConverter.ACCESS_COOKIE, tokenCookie)
                .exchange().expectStatus().isOk();

        var recibido = ultimoRequestAlDestino();
        assertThat(recibido.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("service");
        assertThat(recibido.getHeader(IdentityHeaders.SERVICE_ID)).isEqualTo("cursos-service");
    }
}
