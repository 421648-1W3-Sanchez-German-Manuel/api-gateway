package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.filters.CorrelationIdFilter;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

/**
 * El puente que faltaba entre el contexto de Reactor y el MDC.
 *
 * `CorrelationIdFilter` publica el `requestId` en el CONTEXTO DE REACTOR, que es
 * lo correcto: en WebFlux un request salta de hilo y un ThreadLocal se pierde.
 * Pero `logback-spring.xml` lo imprime con `%X{requestId}`, y `%X` lee el MDC,
 * que ES un ThreadLocal. Los dos extremos estaban bien y no habia nada en el
 * medio: cada linea de log salia con el campo vacio.
 *
 * El sintoma no se parecia a un bug. El id se generaba, viajaba al destino por
 * header y volvia en la respuesta; lo unico que faltaba era justo donde se
 * necesita, que es el log. Se ve asi:
 *
 *   [api-gateway,,,] LoggingFilter - GET /api/users/me -> 200 (9 ms)
 *
 * Tres campos vacios entre las comas. Criterio 9 del DoD: seguir un request de
 * punta a punta cruzando logs del Gateway y de un microservicio con un solo id.
 * Con el campo vacio no se puede seguir nada.
 *
 * Como se arregla: se registra un {@link ThreadLocalAccessor} para la clave, y
 * Reactor restaura el MDC alrededor de cada señal.
 *
 * El enganche de Reactor YA estaba puesto: `spring.reactor.context-propagation:
 * auto` en el application.yml, con un comentario que explica este mismo
 * problema. Lo unico que faltaba era el accessor, porque la propagacion
 * automatica solo mueve las claves que alguien registro. Los dos extremos
 * estaban bien y el del medio no existia.
 */
@Configuration
public class CorrelationMdcConfig {

    @PostConstruct
    void registrarPuente() {
        // Idempotente: registrar dos veces la misma clave reemplaza, no duplica.
        // Importa porque el contexto de Spring se cachea entre clases de test.
        //
        // No hace falta llamar a Hooks.enableAutomaticContextPropagation(): lo
        // hace Boot por `spring.reactor.context-propagation: auto`.
        ContextRegistry.getInstance().registerThreadLocalAccessor(new RequestIdAccessor());
    }

    /**
     * La clave del accessor tiene que ser EXACTAMENTE la misma que la del
     * contexto de Reactor, o no se restaura nada y el sintoma es identico a no
     * tener el puente. Por eso sale de la constante y no de un literal.
     */
    static final class RequestIdAccessor implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return CorrelationIdFilter.CTX_REQUEST_ID;
        }

        @Override
        public String getValue() {
            return MDC.get(CorrelationIdFilter.CTX_REQUEST_ID);
        }

        @Override
        public void setValue(String value) {
            MDC.put(CorrelationIdFilter.CTX_REQUEST_ID, value);
        }

        /** Al salir del alcance: limpiar, o el id se filtra al request siguiente. */
        @Override
        public void setValue() {
            MDC.remove(CorrelationIdFilter.CTX_REQUEST_ID);
        }
    }
}
