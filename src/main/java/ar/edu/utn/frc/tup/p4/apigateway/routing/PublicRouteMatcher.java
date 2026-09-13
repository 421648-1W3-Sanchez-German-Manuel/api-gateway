package ar.edu.utn.frc.tup.p4.apigateway.routing;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * LA definicion de "ruta publica", en un solo lugar.
 *
 * <p>Antes vivia en dos lenguajes con dos semanticas: {@code SecurityConfig}
 * con Ant y {@code PublicRouteGuard} partiendo el path por "/" y mirando el
 * cuarto segmento. Dos matchers para el mismo concepto divergen en los bordes
 * (trailing slash, segmentos vacios, mayusculas): un cambio en uno y no en
 * otro abre un hueco en una direccion u otra. Ahora ambos consumen esto.
 *
 * <p>Las {@code expensive-routes} del rate-limit son OTRA cosa (lista del
 * operador, no el convenio public de §5) y siguen con su propio matcher.
 */
@Component
public class PublicRouteMatcher {

    /** El convenio §5: /api/{nombre}/public/**. Tambien lo usa SecurityConfig. */
    public static final String API_PUBLIC_PATTERN = "/api/*/public/**";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    public boolean esPublica(String path) {
        if (path == null) {
            return false;
        }
        return MATCHER.match("/.well-known/**", path)
                || MATCHER.match("/fallback/**", path)
                || MATCHER.match(API_PUBLIC_PATTERN, path);
    }
}
