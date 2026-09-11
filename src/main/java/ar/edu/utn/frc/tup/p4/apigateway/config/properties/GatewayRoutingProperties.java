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
     * <p>La UNICA derivacion que existe, y va en un solo sentido. La inversa
     * (segmento -> serviceId) no esta y no debe estar: reconstruirla obliga a
     * asumir que todo serviceId termina en {@code -service}, y un equipo que se
     * registre como {@code cursos} a secas rompe esa vuelta sin que nada avise.
     * Donde hace falta el destino resuelto -{@code ServiceAudienceFilter}- se
     * lee del host de la ruta, que es el serviceId exacto de la allowlist.
     */
    public String serviceIdToPathSegment(String serviceId) {
        return serviceId.toLowerCase(Locale.ROOT).replace(serviceIdSuffix, "");
    }
}
