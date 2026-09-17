package ar.edu.utn.frc.tup.p4.apigateway.security;

import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The identity ALREADY VALIDATED, in an immutable record. Everything that goes
 * out as a header is derived from here and only from here — never from an
 * incoming header.
 */
public record PrincipalContext(PrincipalType type, String subject, List<String> roles,
                               List<String> scopes, String sid, String accountStatus,
                               Boolean passwordChangeRequired, Boolean onboardingPending,
                               String onBehalfOf) {

    public static PrincipalContext from(Jwt jwt) {
        PrincipalType type = PrincipalType.from(jwt.getClaimAsString("type"));
        List<String> roles = normalize(jwt.getClaimAsStringList("roles"));

        if (type == PrincipalType.USER) {
            return new PrincipalContext(type, jwt.getSubject(), roles, List.of(),
                    jwt.getClaimAsString("sid"), jwt.getClaimAsString("est"),
                    booleanClaim(jwt, "pwd"), booleanClaim(jwt, "onb"), null);
        }

        List<String> scopes = normalize(Arrays.asList(
                Optional.ofNullable(jwt.getClaimAsString("scope")).orElse("").split("[\\s,]+")));

        return new PrincipalContext(type, jwt.getSubject(), roles, scopes,
                null, null, null, null, jwt.getClaimAsString("on_behalf_of"));
    }

    /** DEC-05: comma with no space, no empties, no duplicates, no trailing comma. */
    public String rolesHeader() {
        return String.join(",", roles);
    }

    /** DEC-05: roles (MS) FIRST, then the scopes. Stable order. */
    public String scopesHeader() {
        return Stream.concat(roles.stream(), scopes.stream())
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /**
     * Reads a boolean claim without assuming its JSON type. If users-service
     * emits {@code "true"} (string) instead of {@code true}, the generic
     * {@code jwt.getClaim("pwd")} throws {@code ClassCastException} and the
     * gateway answers 500 because of an innocent change by the other team.
     * Unknown or missing type -> null (the caller decides).
     */
    public static Boolean booleanClaim(Jwt jwt, String claim) {
        Object v = jwt.getClaims().get(claim);
        return switch (v) {
            case null -> null;
            case Boolean b -> b;
            case String s -> "true".equalsIgnoreCase(s.trim()) ? Boolean.TRUE
                    : "false".equalsIgnoreCase(s.trim()) ? Boolean.FALSE : null;
            case Number n -> n.intValue() != 0;
            default -> null;
        };
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .distinct()
                .toList();
    }
}
