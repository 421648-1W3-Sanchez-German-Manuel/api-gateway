package ar.edu.utn.frc.tup.p4.apigateway.security;

import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrincipalContextFactoryTest {

    private Jwt.Builder base() {
        return Jwt.withTokenValue("x").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("iss", "users-service");
    }

    @Test
    void a_person_token_is_mapped_in_full() {
        UUID sub = UUID.randomUUID();
        Jwt jwt = base().subject(sub.toString()).claim("type", "user")
                .claim("roles", List.of("STUDENT", "PROFESSOR")).claim("sid", "sid-1")
                .claim("est", "ACTIVE").claim("pwd", false).claim("onb", false).build();

        PrincipalContext p = PrincipalContext.from(jwt);

        assertThat(p.type()).isEqualTo(PrincipalType.USER);
        assertThat(p.subject()).isEqualTo(sub.toString());
        assertThat(p.roles()).containsExactly("STUDENT", "PROFESSOR");
        assertThat(p.sid()).isEqualTo("sid-1");
        assertThat(p.est()).isEqualTo("ACTIVE");
    }

    @Test
    void serializa_los_roles_con_coma_SIN_espacio() {
        // DEC-05: del lado del destino, un solo split(",") sirve.
        Jwt jwt = base().subject("s").claim("type", "user")
                .claim("roles", List.of("STUDENT", "PROFESSOR")).claim("sid", "s")
                .claim("est", "ACTIVE").claim("pwd", false).claim("onb", false).build();

        assertThat(PrincipalContext.from(jwt).rolesHeader()).isEqualTo("STUDENT,PROFESSOR");
    }

    @Test
    void el_header_de_servicio_lleva_MS_PRIMERO_y_despues_los_scopes() {
        // DEC-05: "MS + the token scope", en ese orden y estable.
        Jwt jwt = base().subject("cursos-service").claim("type", "service")
                .claim("roles", List.of("MS")).claim("aud", List.of("users-service"))
                .claim("scope", "users.profile.read").build();

        assertThat(PrincipalContext.from(jwt).scopesHeader()).isEqualTo("MS,users.profile.read");
    }

    @Test
    void emits_no_empty_values_no_trailing_comma_and_no_duplicates() {
        Jwt jwt = base().subject("cursos-service").claim("type", "service")
                .claim("roles", List.of("MS")).claim("aud", List.of("users-service"))
                .claim("scope", "users.profile.read  users.profile.read ").build();

        String header = PrincipalContext.from(jwt).scopesHeader();
        assertThat(header).isEqualTo("MS,users.profile.read");
        assertThat(header).doesNotEndWith(",").doesNotContain(",,").doesNotContain(", ");
    }

    @Test
    void an_unknown_type_is_rejected() {
        Jwt jwt = base().subject("s").claim("type", "robot").build();
        assertThatThrownBy(() -> PrincipalContext.from(jwt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_service_token_carries_neither_sid_nor_est() {
        Jwt jwt = base().subject("cursos-service").claim("type", "service")
                .claim("roles", List.of("MS")).claim("aud", List.of("users-service"))
                .claim("scope", "users.profile.read").build();

        PrincipalContext p = PrincipalContext.from(jwt);
        assertThat(p.sid()).isNull();
        assertThat(p.est()).isNull();
    }
}
