package ar.edu.utn.frc.tup.p4.apigateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway's spec and, above all, THE documentation screen of the
 * subsystem.
 *
 * <p>The gateway has a single endpoint of its own, so documenting it is not
 * the point. The point is the {@code springdoc.swagger-ui.urls} dropdown: a
 * single place with ALL the platform's APIs. That this place is the gateway
 * is not chance — it is the only process that already knows every service,
 * and the only one that publishes a port.
 *
 * <p><b>This screen does not route.</b> That an API appears in the dropdown
 * does not expose it: exposing it still means putting its serviceId in
 * {@code gateway.routing.allowlist} (non-negotiable 2). They are two lists
 * with two purposes, and adding the docs of a service that is not yet on the
 * allowlist gives 404 when you try it, not access.
 */
@Configuration
public class OpenApiConfig {

    /** The scheme's name, in case an endpoint of its own ever needs it. */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI apiGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("api-gateway · Plataforma Gamificada TUP")
                        .version("v1")
                        .description("""
                                La unica puerta de entrada del subsistema. Autentica, propaga
                                identidad y aplica resiliencia; **no autoriza por rol** — eso lo
                                decide cada servicio destino.

                                ### Como usar esta pantalla

                                Arriba a la derecha hay un desplegable con el spec de cada
                                servicio. Para probar un endpoint privado:

                                1. Elegi `users-service` y sacate un token con
                                   `/auth/login` + `/auth/2fa/verify` (el code sale por el buzon
                                   de desarrollo, en `/dev/`).
                                2. **Esperá ~4 segundos.** El gateway cachea el estado de sesion
                                   3 s: un token recien emitido puede rebotar dentro de esa
                                   ventana, y lo mismo pasa al reves despues de un logout.
                                3. Apreta **Authorize** y pega el access token.

                                ### Que esperar cuando algo falla

                                Todos los errores son `application/problem+json` y se ramifican
                                por `type`, nunca por el status:

                                | Status | Que significa |
                                |---|---|
                                | 404 `route-not-found` | El servicio no esta en la allowlist. No esta expuesto. |
                                | 503 `service-unavailable` | Esta en la allowlist pero no tiene instancias arriba. Trae `Retry-After`. |
                                | 401 | Falta identidad valida, o la sesion dejo de ser la vigente. |
                                | 429 `too-many-attempts` | Tope por IP. |

                                Un 503 y un 404 dicen cosas distintas a proposito: un servicio
                                caido **existe**, y contestar 404 mandaria a buscar el error en
                                el lugar equivocado.""")
                        )
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        El access token que emite users-service. El gateway valida
                                        firma, `iss` y `exp` contra el JWKS, y ademas que el `sid`
                                        siga siendo la sesion vigente.""")));
    }
}
