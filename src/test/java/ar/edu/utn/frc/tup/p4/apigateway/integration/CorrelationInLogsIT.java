package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.filters.CorrelationIdFilter;
import ar.edu.utn.frc.tup.p4.apigateway.filters.LoggingFilter;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterio 9 del DoD: se puede seguir un request de punta a punta cruzando logs
 * del Gateway y de un microservicio CON UN SOLO ID.
 *
 * El criterio decia "inspeccion del logback-spring.xml + assert sobre el
 * appender". Inspeccionar el XML no alcanzaba: el pattern tenia `%X{requestId}`
 * y estaba perfecto, el filtro publicaba el id en el contexto de Reactor y
 * tambien estaba perfecto, y el campo salia vacio igual porque `%X` lee el MDC
 * y nadie escribia ahi. Un criterio que se verifica leyendo un archivo de
 * configuracion no prueba comportamiento.
 *
 * Este test afirma sobre el MDC del evento de log real, generado por un request
 * que atraveso el pipeline completo por HTTP.
 */
class CorrelationInLogsIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private ListAppender<ILoggingEvent> capturados;

    @BeforeEach
    void engancharAppender() {
        capturados = new ListAppender<>();
        capturados.start();
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).addAppender(capturados);
    }

    @AfterEach
    void soltarAppender() {
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).detachAppender(capturados);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void el_requestId_entrante_aparece_en_el_MDC_de_la_linea_de_log() {
        UUID usuario = UUID.randomUUID();
        seedSession(redis, usuario, "sid-1");
        String mio = "rid-" + UUID.randomUUID();

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(usuario, "sid-1"))
                .header(IdentityHeaders.REQUEST_ID, mio)
                .exchange()
                .expectStatus().isOk()
                // Vuelve en la respuesta: es el id que una persona puede reportar.
                .expectHeader().valueEquals(IdentityHeaders.REQUEST_ID, mio);

        assertThat(capturados.list)
                .describedAs("el LoggingFilter tiene que haber logueado el request")
                .isNotEmpty();

        assertThat(capturados.list)
                .describedAs("toda linea del request lleva SU requestId en el MDC")
                .anySatisfy(evento -> assertThat(
                        evento.getMDCPropertyMap().get(CorrelationIdFilter.CTX_REQUEST_ID))
                        .isEqualTo(mio));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void sin_header_entrante_el_MDC_igual_lleva_el_id_generado() {
        // La mayoria de los requests llegan sin `X-Request-Id`: los genera el
        // Gateway. Si el puente solo funcionara con el header entrante, el log
        // quedaria vacio justo en el caso comun.
        UUID usuario = UUID.randomUUID();
        seedSession(redis, usuario, "sid-1");

        var respuesta = cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(usuario, "sid-1"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(IdentityHeaders.REQUEST_ID)
                .returnResult(String.class);

        String generado = respuesta.getResponseHeaders().getFirst(IdentityHeaders.REQUEST_ID);

        assertThat(capturados.list)
                .describedAs("el id del MDC es el MISMO que volvio al cliente")
                .anySatisfy(evento -> assertThat(
                        evento.getMDCPropertyMap().get(CorrelationIdFilter.CTX_REQUEST_ID))
                        .isEqualTo(generado));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void el_traceId_del_traceparent_entrante_aparece_en_el_MDC_de_la_linea_de_log() {
        // W3C Trace Context: el Gateway conserva el traceparent entrante y
        // deriva traceId/spanId de ahi. Son los MISMOS valores que viajan al
        // destino y que users-service imprime, asi que se puede cruzar la linea
        // del Gateway con la del microservicio (criterio 9 del DoD).
        UUID usuario = UUID.randomUUID();
        seedSession(redis, usuario, "sid-1");
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(usuario, "sid-1"))
                .header("traceparent", traceparent)
                .exchange()
                .expectStatus().isOk();

        assertThat(capturados.list)
                .describedAs("el traceId del traceparent entrante tiene que salir en el MDC")
                .anySatisfy(evento -> {
                    Map<String, String> mdc = evento.getMDCPropertyMap();
                    assertThat(mdc).containsEntry(
                            CorrelationIdFilter.CTX_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
                    assertThat(mdc).containsEntry(
                            CorrelationIdFilter.CTX_SPAN_ID, "00f067aa0ba902b7");
                });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void sin_traceparent_entrante_el_MDC_igual_lleva_un_traceId_generado() {
        // El caso comun: el Gateway genera el traceparent. El log tiene que
        // mostrar ese traceId/spanId aunque no haya llegado ninguno.
        UUID usuario = UUID.randomUUID();
        seedSession(redis, usuario, "sid-1");

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(usuario, "sid-1"))
                .exchange()
                .expectStatus().isOk();

        assertThat(capturados.list)
                .describedAs("toda linea del request lleva un traceId valido generado por el Gateway")
                .anySatisfy(evento -> {
                    Map<String, String> mdc = evento.getMDCPropertyMap();
                    assertThat(mdc).containsKey(CorrelationIdFilter.CTX_TRACE_ID);
                    assertThat(mdc.get(CorrelationIdFilter.CTX_TRACE_ID)).matches("[0-9a-f]{32}");
                    assertThat(mdc.get(CorrelationIdFilter.CTX_SPAN_ID)).matches("[0-9a-f]{16}");
                });
    }
}
