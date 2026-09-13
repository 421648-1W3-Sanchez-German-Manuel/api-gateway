package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

/**
 * Las DOS formas en que la cadena de Security corta un request, en un solo
 * lugar y con el mismo contrato RFC 9457 del resto del pipeline.
 *
 * <ul>
 *   <li>Sin token o token invalido (firma, {@code exp}, {@code iss}) ->
 *       401 {@code not-authenticated}. El cuerpo NO nombra el claim que
 *       falto: eso va solo al log (DEC-44).</li>
 *   <li>Autenticado pero sin acceso -> 403 {@code access-denied}, con mensaje
 *       fijo y sanitizado.</li>
 * </ul>
 */
public final class SecurityProblemHandlers {

    private SecurityProblemHandlers() {
    }

    public static ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, denegado) -> ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                ErrorTypes.NOT_AUTHENTICATED, "No autenticado",
                "El token no es valido o esta ausente.");
    }

    public static ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denegado) -> ProblemDetails.write(exchange, HttpStatus.FORBIDDEN,
                ErrorTypes.ACCESS_DENIED, "Acceso denegado",
                "No tiene permiso para acceder a este recurso.");
    }
}
