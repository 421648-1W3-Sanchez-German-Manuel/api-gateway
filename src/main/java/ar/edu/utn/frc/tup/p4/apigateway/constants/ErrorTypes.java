package ar.edu.utn.frc.tup.p4.apigateway.constants;

import java.net.URI;

/**
 * Los MISMOS type que devuelve users-service: un solo espacio de firstNames de
 * errores para toda la plataforma, asi el frontend tiene una sola rama de
 * manejo por cada situacion y no le importa quien contesto.
 */
public final class ErrorTypes {

    private static final String BASE = "https://tpi.utn.frc/errors/";

    public static final URI NOT_AUTHENTICATED = URI.create(BASE + "not-authenticated");
    public static final URI SESSION_CLOSED = URI.create(BASE + "session-closed");
    public static final URI SESSION_SUPERSEDED = URI.create(BASE + "session-superseded");
    public static final URI AUDIENCIA_INVALIDA = URI.create(BASE + "invalid-audience");
    public static final URI PENDING_ACCOUNT = URI.create(BASE + "pending-account");
    public static final URI PASSWORD_CHANGE_REQUIRED = URI.create(BASE + "password-change-required");
    public static final URI ONBOARDING_PENDING = URI.create(BASE + "onboarding-pending");
    public static final URI SERVICIO_NO_DISPONIBLE = URI.create(BASE + "service-unavailable");
    public static final URI ROUTE_NOT_FOUND = URI.create(BASE + "route-not-found");
    /** DEC-24: el MISMO que usa auth/ para su limite por email. */
    public static final URI TOO_MANY_ATTEMPTS = URI.create(BASE + "too-many-attempts");

    private ErrorTypes() {
    }
}
