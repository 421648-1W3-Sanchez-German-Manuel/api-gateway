package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subsystem's documentation screen is served, is anonymous, and opens
 * nothing that was closed.
 *
 * <p>Everything hangs off {@code /api/docs/**} and each piece of the path is a
 * constraint, not a preference — the {@code springdoc} block of
 * {@code application.yml} lists them. The three this test pins:
 *
 * <ol>
 *   <li><b>It starts with /api/</b>: it is the only thing nginx proxies here.
 *       Outside that the request falls into the SPA and its {@code index.html}
 *       comes back, a 200 with HTML that looks nothing like a routing
 *       problem.</li>
 *   <li><b>It is not routed</b>: "docs" is not a serviceId in the allowlist, so
 *       no route matches and this process handles it. If it were routed, the
 *       destination would answer anything but a spec.</li>
 *   <li><b>The permitAll does not leak</b>: opening {@code /api/docs/**} has to
 *       leave {@code /api/users/**} exactly as closed.</li>
 * </ol>
 */
class OpenApiIT extends AbstractGatewayTest {

    private static final String SPEC = "/api/docs/v3/api-docs";
    private static final String UI = "/api/docs/ui";
    private static final String UI_INDEX = "/api/docs/swagger-ui/index.html";

    @Test
    void the_gateway_spec_is_served_without_a_token() {
        client.get().uri(SPEC)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").exists()
                .jsonPath("$.info.title").value(t -> assertThat((String) t).contains("api-gateway"));
    }

    @Test
    void the_ui_redirects_to_its_index() {
        // NOTE: springdoc hangs the static assets off the PARENT of
        // swagger-ui.path, not off the path. That is why the path has an extra
        // segment (/ui): with a bare /api/docs the assets would go to
        // /api/swagger-ui/**, outside the permitAll, and a second hole would
        // have to be opened.
        client.get().uri(UI)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().value("Location", loc -> assertThat(loc).endsWith(UI_INDEX));
    }

    @Test
    void the_swagger_ui_static_assets_are_served() {
        client.get().uri(UI_INDEX).exchange().expectStatus().isOk();
        // The bundle and not only the index: the index can be served by
        // IndexPageTransformer and the bundle cannot, so testing only the index
        // would let a blank screen pass the test.
        client.get().uri("/api/docs/swagger-ui/swagger-ui-bundle.js")
                .exchange().expectStatus().isOk();
    }

    @Test
    void the_dropdown_lists_the_platform_specs() {
        // THIS is the gateway's contribution. Documenting its single endpoint
        // does not justify the dependency; being the only place where all the
        // APIs are, does.
        client.get().uri(SPEC + "/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls[?(@.url=='/api/users/public/v3/api-docs')]").exists()
                .jsonPath("$.urls[?(@.url=='/api/docs/v3/api-docs')]").exists();
    }

    @Test
    void the_dropdown_names_come_out_without_mojibake() {
        // A non-ASCII value in application.yml comes out wrongly encoded
        // ("Tema 01 Â· ...") even though the file's bytes are UTF-8 and the JVM
        // runs with file.encoding=UTF-8: the YAML loader does not decode them as
        // UTF-8. The same character in a .java comes out fine.
        //
        // It asserts on the symptom and not on "it is ASCII" because what
        // matters is that the name reaches the browser legibly, wherever it
        // comes from. The 'Â' is the signature of that double encoding.
        client.get().uri(SPEC + "/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("dropdown name wrongly encoded")
                        .doesNotContain("Â"));
    }

    @Test
    void opening_the_docs_does_not_open_the_endpoints() {
        // The permitAll is for /api/docs/**, not /api/**. One extra character in
        // that pattern would leave the whole platform without authentication,
        // and the docs would still look the same: the symptom of that mistake is
        // that there is NO symptom. That is why it asserts on what has to stay
        // closed.
        client.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/users/whitelist").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void the_docs_are_not_routed_to_any_service() {
        // "docs" is not in the allowlist. If any route matched it, this would
        // return the destination MockWebServer's "ok" instead of the spec, and
        // the day a docs-service exists the screen would disappear without
        // anyone touching this repo.
        client.get().uri(SPEC)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .as("the gateway handles it, not a routed destination")
                        .isNotEqualTo("ok")          // the MockWebServer's exact body
                        .contains("\"openapi\""));
    }
}
