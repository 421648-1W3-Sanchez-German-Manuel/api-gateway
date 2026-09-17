package ar.edu.utn.frc.tup.p4.apigateway.routing;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * THE definition of "public route", in one single place.
 *
 * <p>It used to live in two languages with two semantics: {@code SecurityConfig}
 * with Ant and {@code PublicRouteGuard} splitting the path by "/" and looking
 * at the fourth segment. Two matchers for the same concept diverge at the
 * edges (trailing slash, empty segments, uppercase): changing one but not the
 * other opens a gap in one direction or another. Now both consume this.
 *
 * <p>The rate-limit's {@code expensive-routes} are ANOTHER thing (an operator
 * list, not the §5 public convention) and keep their own matcher.
 */
@Component
public class PublicRouteMatcher {

    /** The §5 convention: /api/{name}/public/**. SecurityConfig uses it too. */
    public static final String API_PUBLIC_PATTERN = "/api/*/public/**";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    public boolean isPublic(String path) {
        if (path == null) {
            return false;
        }
        return MATCHER.match("/.well-known/**", path)
                || MATCHER.match("/fallback/**", path)
                || MATCHER.match(API_PUBLIC_PATTERN, path);
    }
}
