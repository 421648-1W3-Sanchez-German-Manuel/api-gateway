package ar.edu.utn.frc.tup.p4.apigateway.support;

import com.redis.testcontainers.RedisContainer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Base de los tests de integracion. Levanta:
 *  - un MockWebServer que sirve el JWKS con la key de TokenFactory;
 *  - un MockWebServer que hace de microservicio destino y REGISTRA lo que
 *    recibe, para poder afirmar sobre los headers inyectados;
 *  - Redis real, porque la sesion unica y el fail-mode de DEC-01 no se
 *    pueden simular con un mock sin perder justamente lo que se quiere probar.
 *
 * PATRON SINGLETON, deliberado: los tres recursos se arrancan UNA vez para
 * toda la JVM y no se apagan al terminar cada clase.
 *
 * Con @Testcontainers + @Container, JUnit apaga el contenedor al terminar la
 * clase y lo vuelve a levantar en OTRO PUERTO para la siguiente — pero Spring
 * CACHEA el contexto entre clases, asi que el contexto reusado sigue apuntando
 * al puerto viejo y todo falla con RedisCommandTimeoutException. Los tests
 * pasan de a uno y fallan corridos: el sintoma mas confuso posible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractGatewayTest {

    protected static final RedisContainer REDIS;
    protected static final MockWebServer JWKS;
    protected static final MockWebServer DESTINO;

    static {
        REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
        REDIS.start();

        JWKS = new MockWebServer();
        JWKS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(TokenFactory.jwksJson());
            }
        });

        DESTINO = new MockWebServer();
        DESTINO.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });

        try {
            JWKS.start();
            DESTINO.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the MockWebServer instances", e);
        }
        // They are never stopped: they live as long as the JVM does. Ryuk cleans them up.
    }

    @LocalServerPort
    protected int puerto;

    protected WebTestClient cliente;

    /**
     * Se construye a mano contra el puerto real en vez de inyectar el bean:
     * asi queda explicito que los requests atraviesan el pipeline COMPLETO
     * por HTTP, no un mock del contexto. El timeout largo es para los tests
     * de resiliencia, que provocan destinos lentos a proposito.
     */
    @BeforeEach
    void armarCliente() throws InterruptedException {
        cliente = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + puerto)
                .responseTimeout(Duration.ofSeconds(30))
                .build();

        // El MockWebServer es singleton de JVM, asi que su cola de requests es
        // COMPARTIDA entre clases de test. Sin drenarla, un takeRequest()
        // devuelve el request que dejo otro test y la asercion mide otra cosa.
        // Vaciarla en cada test es lo que hace que el orden de ejecucion no
        // cambie el resultado.
        while (DESTINO.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // drenar
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> JWKS.url("/.well-known/jwks.json").toString());

        // DISCOVERY SIMPLE, no rutas estaticas. Las rutas las genera el
        // AllowlistRouteLocator de produccion a partir de la allowlist, y el
        // load balancer resuelve lb://{serviceId} contra estas instancias.
        //
        // Antes esto eran rutas estaticas apuntando directo al MockWebServer.
        // Se veia mas simple y era mentira: dejaba SIN PROBAR la generacion de
        // rutas, que es donde estaba el bug del locator SpEL — el Gateway
        // levantaba con la tabla vacia y contestaba 404 a todo, con los 101
        // tests en verde. Un test que reemplaza el mecanismo que dice probar
        // no test nada.
        //
        // De paso, con uri lb://users-service el ServiceAudienceFilter resuelve
        // el destino por el HOST de la ruta, que es la rama que corre en
        // produccion; con http://localhost:PORT caia siempre en la derivacion
        // por path, o sea probaba la rama de respaldo.
        String destinoUri = "http://localhost:" + DESTINO.getPort();

        r.add("spring.cloud.discovery.client.simple.instances.users-service[0].uri",
                () -> destinoUri);
        // Un micro ajeno, para que AccountStateGuardIT y ServiceAudienceIT
        // puedan probar el gate grueso y el aud acotado a otro destino.
        r.add("spring.cloud.discovery.client.simple.instances.cursos-service[0].uri",
                () -> destinoUri);
        r.add("gateway.routing.allowlist", () -> "users-service,cursos-service");

        // Eureka no: alcanza con el discovery simple, que no necesita servidor.
        r.add("eureka.client.enabled", () -> "false");
    }

    /** Consume y devuelve el ultimo request que llego al destino. */
    protected RecordedRequest ultimoRequestAlDestino() throws InterruptedException {
        return DESTINO.takeRequest();
    }
}
