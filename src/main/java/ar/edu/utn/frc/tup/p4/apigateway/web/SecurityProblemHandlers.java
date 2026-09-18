package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

/**
 * The TWO ways the Security chain cuts a request, in one single place and
 * with the same RFC 9457 contract as the rest of the pipeline.
 *
 * <ul>
 *   <li>No token or invalid token (signature, {@code exp}, {@code iss}) ->
 *       401 {@code not-authenticated}. The body does NOT name the claim that
 *       failed: that goes only to the log (DEC-44).</li>
 *   <li>Authenticated but no access -> 403 {@code access-denied}, with a
 *       fixed, sanitized message.</li>
 * </ul>
 */
public final class SecurityProblemHandlers {

    private SecurityProblemHandlers() {
    }

    public static ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, denied) -> ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                ErrorTypes.NOT_AUTHENTICATED, "Not authenticated",
                "The token is invalid or absent.");
    }

    public static ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denied) -> ProblemDetails.write(exchange, HttpStatus.FORBIDDEN,
                ErrorTypes.ACCESS_DENIED, "Access denied",
                "You do not have permission to access this resource.");
    }
}
