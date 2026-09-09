package ar.edu.utn.frc.tup.p4.apigateway.constants;

import java.util.List;

/** The contract from section 06 of the gateway manifesto. Do not invent new names. */
public final class IdentityHeaders {

    public static final String PRINCIPAL_TYPE = "X-Principal-Type";
    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";
    public static final String SERVICE_ID = "X-Service-Id";
    public static final String SERVICE_SCOPES = "X-Service-Scopes";
    public static final String REQUEST_ID = "X-Request-Id";

    /**
     * Los cinco que el Gateway BORRA siempre antes de inyectar. Anti-spoofing.
     * traceparent NO esta aca: el entrante se acepta (W3C Trace Context),
     * porque pisarlo parte el rastreo en dos justo en el borde.
     */
    public static final List<String> RESERVED = List.of(
            PRINCIPAL_TYPE, USER_ID, USER_ROLES, SERVICE_ID, SERVICE_SCOPES);

    private IdentityHeaders() {
    }
}
