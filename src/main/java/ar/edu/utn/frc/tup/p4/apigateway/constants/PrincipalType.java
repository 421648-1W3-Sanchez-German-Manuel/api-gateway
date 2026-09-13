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
     * Comparacion null-safe contra el claim {@code type}, sin excepciones.
     * Los guards la usan en vez de comparar strings sueltos: un literal
     * {@code "User"} o {@code "SERVICE"} escrito a mano nunca matchea y la
     * rama muere en silencio.
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
