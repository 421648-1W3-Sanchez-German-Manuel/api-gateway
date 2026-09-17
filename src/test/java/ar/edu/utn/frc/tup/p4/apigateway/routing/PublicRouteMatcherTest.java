package ar.edu.utn.frc.tup.p4.apigateway.routing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRouteMatcherTest {

    private final PublicRouteMatcher matcher = new PublicRouteMatcher();

    @ParameterizedTest(name = "{0} -> public={1}")
    @CsvSource({
            // §5 convention
            "/api/users/public/auth/login,          true",
            "/api/users/public/registration,       true",
            "/api/users/public,                    true",
            "/api/users/public/,                   true",
            "/api/cursos/public/loquesea,          true",
            // edges that are NOT public
            "/api/users/publicx,                   false",
            "/api/users/Public/auth,               false",
            "/api/users/me,                        false",
            "/api/users,                           false",
            "/api,                                 false",
            "/,                                    false",
            // infrastructure
            "/.well-known/jwks.json,               true",
            "/fallback/servicio,                   true",
    })
    void classifies_public_and_private_routes(String path, boolean expected) {
        assertThat(matcher.isPublic(path.trim())).isEqualTo(expected);
    }
}
