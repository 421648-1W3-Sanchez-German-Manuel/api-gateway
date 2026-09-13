package ar.edu.utn.frc.tup.p4.apigateway.routing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRouteMatcherTest {

    private final PublicRouteMatcher matcher = new PublicRouteMatcher();

    @ParameterizedTest(name = "{0} -> publica={1}")
    @CsvSource({
            // convenio §5
            "/api/users/public/auth/login,          true",
            "/api/users/public/registration,       true",
            "/api/users/public,                    true",
            "/api/users/public/,                   true",
            "/api/cursos/public/loquesea,          true",
            // bordes que NO son publicos
            "/api/users/publicx,                   false",
            "/api/users/Public/auth,               false",
            "/api/users/me,                        false",
            "/api/users,                           false",
            "/api,                                 false",
            "/,                                    false",
            // infraestructura
            "/.well-known/jwks.json,               true",
            "/fallback/servicio,                   true",
    })
    void clasifica_rutas_publicas_y_privadas(String path, boolean esperada) {
        assertThat(matcher.esPublica(path.trim())).isEqualTo(esperada);
    }
}
