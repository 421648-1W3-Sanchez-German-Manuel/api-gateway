package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La pantalla de documentacion del subsistema se sirve, es anonima, y no abre
 * nada que estuviera cerrado.
 *
 * <p>Todo cuelga de {@code /api/docs/**} y cada pedazo del path es una
 * restriccion, no una preferencia — el bloque {@code springdoc} de
 * {@code application.yml} las lista. Las tres que este test fija:
 *
 * <ol>
 *   <li><b>Arranca con /api/</b>: es lo unico que nginx proxea hasta aca.
 *       Fuera de ahi el request cae en la SPA y vuelve su {@code index.html},
 *       un 200 con HTML que no se parece en nada a un problema de ruteo.</li>
 *   <li><b>No se rutea</b>: "docs" no es un serviceId de la allowlist, asi que
 *       ninguna ruta matchea y lo atiende este proceso. Si se ruteara, el
 *       destino contestaria cualquier cosa menos un spec.</li>
 *   <li><b>El permitAll no se filtra</b>: abrir {@code /api/docs/**} tiene que
 *       dejar {@code /api/users/**} exactamente igual de cerrado.</li>
 * </ol>
 */
class OpenApiIT extends AbstractGatewayTest {

    private static final String SPEC = "/api/docs/v3/api-docs";
    private static final String UI = "/api/docs/ui";
    private static final String UI_INDEX = "/api/docs/swagger-ui/index.html";

    @Test
    void el_spec_del_gateway_se_sirve_sin_token() {
        cliente.get().uri(SPEC)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").exists()
                .jsonPath("$.info.title").value(t -> assertThat((String) t).contains("api-gateway"));
    }

    @Test
    void la_pantalla_redirige_a_su_index() {
        // OJO: springdoc cuelga los estaticos del PADRE de swagger-ui.path, no
        // del path. Por eso el path tiene un segmento de mas (/ui): con
        // /api/docs pelado los assets irian a /api/swagger-ui/**, fuera del
        // permitAll, y habria que abrir un segundo agujero.
        cliente.get().uri(UI)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().value("Location", loc -> assertThat(loc).endsWith(UI_INDEX));
    }

    @Test
    void los_estaticos_de_swagger_ui_se_sirven() {
        cliente.get().uri(UI_INDEX).exchange().expectStatus().isOk();
        // El bundle y no solo el index: el index lo puede servir el
        // IndexPageTransformer y el bundle no, asi que probando solo el index
        // una pantalla en blanco pasaria el test.
        cliente.get().uri("/api/docs/swagger-ui/swagger-ui-bundle.js")
                .exchange().expectStatus().isOk();
    }

    @Test
    void el_desplegable_lista_los_specs_de_la_plataforma() {
        // ESTE es el aporte del gateway. Documentar su unico endpoint no
        // justifica la dependencia; ser el unico lugar donde estan todas las
        // APIs, si.
        cliente.get().uri(SPEC + "/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls[?(@.url=='/api/users/public/v3/api-docs')]").exists()
                .jsonPath("$.urls[?(@.url=='/api/docs/v3/api-docs')]").exists();
    }

    @Test
    void abrir_la_doc_no_abre_los_endpoints() {
        // El permitAll es de /api/docs/**, no de /api/**. Un caracter de mas en
        // ese patron dejaria la plataforma entera sin autenticacion, y la doc
        // seguiria viendose igual: el sintoma de ese error es que NO hay
        // sintoma. Por eso se afirma sobre lo que tiene que seguir cerrado.
        cliente.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
        cliente.get().uri("/api/users/whitelist").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void la_doc_no_se_rutea_a_ningun_servicio() {
        // "docs" no esta en la allowlist. Si alguna ruta lo matcheara, esto
        // devolveria el "ok" del MockWebServer destino en vez del spec, y el
        // dia que exista un docs-service la pantalla desapareceria sin que
        // nadie toque este repo.
        cliente.get().uri(SPEC)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(cuerpo -> assertThat(cuerpo)
                        .as("lo atiende el gateway, no un destino ruteado")
                        .isNotEqualTo("ok")          // el cuerpo exacto del MockWebServer
                        .contains("\"openapi\""));
    }
}
