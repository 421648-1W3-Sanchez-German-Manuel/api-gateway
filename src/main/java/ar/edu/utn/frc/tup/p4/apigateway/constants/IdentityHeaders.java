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
     * The five that the Gateway ALWAYS strips before injecting. Anti-spoofing.
     * traceparent is NOT here: the incoming one is accepted (W3C Trace
     * Context), because overwriting it splits the trace in two right at the
     * edge.
     */
    public static final List<String> RESERVED = List.of(
            PRINCIPAL_TYPE, USER_ID, USER_ROLES, SERVICE_ID, SERVICE_SCOPES);

    private IdentityHeaders() {
    }
}
