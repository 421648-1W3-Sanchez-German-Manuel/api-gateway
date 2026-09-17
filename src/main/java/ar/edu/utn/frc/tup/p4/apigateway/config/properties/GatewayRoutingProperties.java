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

    /**
     * users-service -> "users". The derivation from the spec, in one place.
     *
     * <p>The ONLY derivation that exists, and it goes in ONE direction. The
     * inverse (segment -> serviceId) is not there and must not be: rebuilding
     * it forces the assumption that every serviceId ends in {@code -service},
     * and a team registering as plain {@code cursos} breaks that round trip
     * without anything warning. Where the resolved destination is needed —
     * {@code ServiceAudienceFilter} — it is read from the route's host, which
     * is the exact serviceId of the allowlist.
     */
    public String serviceIdToPathSegment(String serviceId) {
        return serviceId.toLowerCase(Locale.ROOT).replace(serviceIdSuffix, "");
    }
}
