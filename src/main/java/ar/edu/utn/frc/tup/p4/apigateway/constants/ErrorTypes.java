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
    public static final URI ACCESS_DENIED = URI.create(BASE + "access-denied");
    public static final URI SESSION_CLOSED = URI.create(BASE + "session-closed");
    public static final URI SESSION_SUPERSEDED = URI.create(BASE + "session-superseded");
    public static final URI INVALID_AUDIENCE = URI.create(BASE + "invalid-audience");
    public static final URI PENDING_ACCOUNT = URI.create(BASE + "pending-account");
    public static final URI PASSWORD_CHANGE_REQUIRED = URI.create(BASE + "password-change-required");
    public static final URI ONBOARDING_PENDING = URI.create(BASE + "onboarding-pending");
    public static final URI SERVICE_UNAVAILABLE = URI.create(BASE + "service-unavailable");
    public static final URI ROUTE_NOT_FOUND = URI.create(BASE + "route-not-found");
    public static final URI METHOD_NOT_ALLOWED = URI.create(BASE + "method-not-allowed");
    /**
     * Paraguas para cualquier otro ResponseStatusException que llegue al
     * handler general (400, 409, 415, ...). Mensajes fijos y sanitizados:
     * nunca se copia el mensaje de la excepcion al cuerpo.
     */
    public static final URI UNEXPECTED_ERROR = URI.create(BASE + "unexpected-error");
    /** DEC-24: el MISMO que usa auth/ para su limite por email. */
    public static final URI TOO_MANY_ATTEMPTS = URI.create(BASE + "too-many-attempts");

    private ErrorTypes() {
    }
}
