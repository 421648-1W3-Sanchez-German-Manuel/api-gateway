package ar.edu.utn.frc.tup.p4.apigateway.constants;

public enum PrincipalType {

    USER("user"),
    SERVICE("service");

    private final String claim;

    PrincipalType(String claim) {
        this.claim = claim;
    }

    public String claim() {
        return claim;
    }

    /**
     * Null-safe comparison against the {@code type} claim, without exceptions.
     * The guards use it instead of comparing loose strings: a hand-written
     * literal like {@code "User"} or {@code "SERVICE"} never matches and the
     * branch dies silently.
     */
    public boolean matches(String value) {
        return claim.equals(value);
    }

    public static PrincipalType from(String value) {
        for (PrincipalType t : values()) {
            if (t.claim.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown token type: " + value);
    }
}
