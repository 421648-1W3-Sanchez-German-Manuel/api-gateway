package ar.edu.utn.frc.tup.p4.apigateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El spec del gateway y, sobre todo, LA pantalla de documentacion del
 * subsistema.
 *
 * <p>El gateway tiene un solo endpoint propio, asi que documentarlo no es el
 * punto. El punto es el desplegable de {@code springdoc.swagger-ui.urls}: un
 * unico lugar donde estan TODAS las APIs de la plataforma. Que ese lugar sea el
 * gateway no es casual — es el unico proceso que ya conoce a todos los
 * servicios, y el unico que publica puerto.
 *
 * <p><b>Esta pantalla no rutea.</b> Que una API aparezca en el desplegable no
 * la expone: exponerla sigue siendo poner su serviceId en
 * {@code gateway.routing.allowlist} (no-negociable 2). Son dos listas con dos
 * propositos, y agregar la doc de un servicio que todavia no esta en la
 * allowlist da 404 al probarlo, no acceso.
 */
@Configuration
public class OpenApiConfig {

    /** El nombre del esquema, por si algun endpoint propio llega a necesitarlo. */
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
