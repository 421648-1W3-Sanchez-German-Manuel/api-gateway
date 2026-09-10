package ar.edu.utn.frc.tup.p4.apigateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * DEC-07 + DEC-44 - `users-service` is a LOGICAL NAME, not a URL: that is why
 * `issuer-uri` is NOT used (Spring treats it as a URL and fires OIDC discovery
 * contra ella).
 *
 * What this validator adds over a bare JwtClaimValidator is the LOG: it names
 * the claim that failed, which turns "everything gives 401" into a grep.
 */
public class IssuerValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(IssuerValidator.class);

    private final String expected;

    public IssuerValidator(String expected) { this.expected = expected; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String iss = jwt.getClaimAsString(JwtClaimNames.ISS);

        if (iss == null) {
            log.warn("JWT_RECHAZADO reason=claim-ausente claim=iss");
            return fallo("El token no declara emisor.");
        }
        if (!expected.equals(iss)) {
            log.warn("JWT_RECHAZADO reason=claim-invalido claim=iss esperado={} recibido={}",
                    expected, iss);
            return fallo("Emisor no reconocido.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult fallo(String description) {
        // The `description` does NOT name the claim: that stays in the log.
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}
