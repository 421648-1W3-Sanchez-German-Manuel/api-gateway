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

    public static PrincipalType from(String value) {
        for (PrincipalType t : values()) {
            if (t.claim.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown token type: " + value);
    }
}
