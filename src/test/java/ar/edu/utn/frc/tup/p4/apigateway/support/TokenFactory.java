package ar.edu.utn.frc.tup.p4.apigateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Firma tokens de test con la MISMA key que AbstractGatewayTest publica
 * como JWKS. Sin esto no se puede probar nada del pipeline: todo request que
 * no sea publico necesita un token que el Gateway acepte.
 */
public final class TokenFactory {

    public static final String KID = "test-kid";

    private static final RSAKey KEY = generate();

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(KID)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo generar la key de test", e);
        }
    }

    /** Lo que sirve el MockWebServer del JWKS. Solo la parte publica. */
    public static String jwksJson() {
        return new JWKSet(KEY).toPublicJWKSet().toString();
    }

    /** Token de persona con TODOS los claims obligatorios (DEC-44). */
    public static String persona(UUID sub, String sid) {
        return persona(sub, sid, b -> { });
    }

    public static String persona(UUID sub, String sid, Consumer<JWTClaimsSet.Builder> ajuste) {
        JWTClaimsSet.Builder b = base()
                .subject(sub.toString())
                .claim("roles", List.of("STUDENT"))
                .claim("type", "user")
                .claim("sid", sid)
                .claim("est", "ACTIVE")
                .claim("pwd", false)
                .claim("onb", false);
        ajuste.accept(b);
        return sign(b.build());
    }

    public static String servicio(String clientId, String aud, String scope) {
        return servicio(clientId, aud, scope, b -> { });
    }

    public static String servicio(String clientId, String aud, String scope,
                                  Consumer<JWTClaimsSet.Builder> ajuste) {
        JWTClaimsSet.Builder b = base()
                .subject(clientId)
                .claim("roles", List.of("MS"))
                .claim("type", "service")
                .claim("scope", scope);
        if (aud != null) {
            b.audience(aud);
        }
        ajuste.accept(b);
        return sign(b.build());
    }

    /**
     * Token firmado con HS256. El Gateway solo admite RS256, asi que este
     * token DEBE ser rechazado — incluido el caso `alg: none`, que Nimbus
     * ni siquiera deja construir con un signer real.
     */
    public static String hs256Falso() {
        try {
            byte[] secreto = new byte[32];
            new java.security.SecureRandom().nextBytes(secreto);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    base().subject(UUID.randomUUID().toString())
                            .claim("type", "user")
                            .claim("roles", List.of("STUDENT"))
                            .claim("sid", "sid-1")
                            .claim("est", "ACTIVE")
                            .claim("pwd", false)
                            .claim("onb", false)
                            .build());
            jwt.sign(new MACSigner(secreto));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder base() {
        Instant ahora = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("users-service")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(600)));
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
            jwt.sign(new RSASSASigner(KEY.toPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token de test", e);
        }
    }

    private TokenFactory() {
    }
}
