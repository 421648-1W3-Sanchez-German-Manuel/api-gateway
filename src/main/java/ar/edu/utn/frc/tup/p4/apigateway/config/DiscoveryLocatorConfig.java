package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * It declares no dynamic routes: {@link AllowlistRouteLocator} generates them
 * from {@code gateway.routing.allowlist}. This class validates the list at
 * startup and exposes the derived serviceId &lt;-&gt; path segment mapping.
 *
 * <p>That generation used to be done by the DiscoveryClient locator's
 * {@code include-expression}. The SpEL {@code serviceId.toLowerCase()} blew up
 * with {@code EL1004E} in the route-refresh listener - NOT at startup - and
 * the Gateway stayed up with an empty table answering 404 to everything, with
 * no visible stack trace. Moving the generation to Java removes the SpEL from
 * the locator and leaves the configuration error where it belongs: at
 * startup.
 */
@Configuration
public class DiscoveryLocatorConfig {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryLocatorConfig.class);

    /** Infrastructure services: routing them through the Gateway is a loop. */
    private static final List<String> FORBIDDEN = List.of("api-gateway", "eureka-server");

    private final GatewayRoutingProperties props;

    public DiscoveryLocatorConfig(GatewayRoutingProperties props) { this.props = props; }

    /** Called from tests and from Spring at startup. That is why it is public. */
    @PostConstruct
    public void validateAllowlist() {
        for (String forbidden : FORBIDDEN) {
            if (props.allowlist().stream()
                    .anyMatch(s -> s.toLowerCase(Locale.ROOT).equals(forbidden))) {
                throw new IllegalStateException(
                        "gateway.routing.allowlist cannot contain '" + forbidden + "': "
                        + "routing the infrastructure through the Gateway produces a loop.");
            }
        }
        log.info("Routing allowlist: {}", props.allowlist());
    }
}
