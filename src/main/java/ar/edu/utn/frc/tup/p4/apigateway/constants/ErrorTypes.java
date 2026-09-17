package ar.edu.utn.frc.tup.p4.apigateway.constants;

import java.net.URI;

/**
 * The SAME types that users-service returns: one single namespace of error
 * types for the whole platform, so the frontend has one handling branch per
 * situation and does not care who answered.
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
     * Umbrella for any other ResponseStatusException that reaches the general
     * handler (400, 409, 415, ...). Fixed, sanitized messages: the exception's
     * message is never copied to the body.
     */
    public static final URI UNEXPECTED_ERROR = URI.create(BASE + "unexpected-error");
    /** DEC-24: the SAME one auth/ uses for its per-email limit. */
    public static final URI TOO_MANY_ATTEMPTS = URI.create(BASE + "too-many-attempts");

    private ErrorTypes() {
    }
}
