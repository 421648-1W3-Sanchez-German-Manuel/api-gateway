package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Locale;

@Validated
@ConfigurationProperties(prefix = "gateway.routing")
public record GatewayRoutingProperties(@NotEmpty List<String> allowlist,
                                       String serviceIdSuffix,
                                       String pathPrefix) {

    /** users-service -> "users". The derivation from the spec, in one place. */
    public String serviceIdToPathSegment(String serviceId) {
        return serviceId.toLowerCase(Locale.ROOT).replace(serviceIdSuffix, "");
    }

    /** "users" -> users-service. The inverse of the previous one. */
    public String pathSegmentToServiceId(String segment) {
        return segment.toLowerCase(Locale.ROOT) + serviceIdSuffix;
    }

    /** true when the string already is a serviceId (users-service) and not a bare host. */
    public boolean looksLikeServiceId(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).endsWith(serviceIdSuffix);
    }

    /**
     * The destination serviceId of a path /api/{name}/..., or null when the
     * path does not follow the convention.
     */
    public String serviceIdFromPath(String path) {
        String[] parts = path.split("/");
        // ["", "api", "users", ...]
        if (parts.length < 3 || !pathPrefix.equals("/" + parts[1])) {
            return null;
        }
        return pathSegmentToServiceId(parts[2]);
    }
}
