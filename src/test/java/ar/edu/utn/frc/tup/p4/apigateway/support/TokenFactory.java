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
 * Signs test tokens with the SAME key that AbstractGatewayTest publishes as
 * JWKS. Without this nothing of the pipeline can be tested: every non-public
 * request needs a token the Gateway accepts.
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
            throw new IllegalStateException("Could not generate the test key", e);
        }
    }

    /** What the JWKS MockWebServer serves. Only the public part. */
    public static String jwksJson() {
        return new JWKSet(KEY).toPublicJWKSet().toString();
    }

    /** Person token with ALL the mandatory claims (DEC-44). */
    public static String person(UUID sub, String sid) {
        return person(sub, sid, b -> { });
    }

    public static String person(UUID sub, String sid, Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder b = base()
                .subject(sub.toString())
                .claim("roles", List.of("STUDENT"))
                .claim("type", "user")
                .claim("sid", sid)
                .claim("est", "ACTIVE")
                .claim("pwd", false)
                .claim("onb", false);
        customizer.accept(b);
        return sign(b.build());
    }

    public static String service(String clientId, String aud, String scope) {
        return service(clientId, aud, scope, b -> { });
    }

    public static String service(String clientId, String aud, String scope,
                                 Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder b = base()
                .subject(clientId)
                .claim("roles", List.of("MS"))
                .claim("type", "service")
                .claim("scope", scope);
        if (aud != null) {
            b.audience(aud);
        }
        customizer.accept(b);
        return sign(b.build());
    }

    /**
     * Token signed with HS256. The Gateway only admits RS256, so this token
     * MUST be rejected — including the `alg: none` case, which Nimbus does
     * not even let you build with a real signer.
     */
    public static String hs256Forgery() {
        try {
            byte[] secret = new byte[32];
            new java.security.SecureRandom().nextBytes(secret);
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
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder base() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("users-service")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
            jwt.sign(new RSASSASigner(KEY.toPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
    }

    private TokenFactory() {
    }
}
