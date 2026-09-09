package ar.edu.utn.frc.tup.p4.apigateway;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayApplicationTest extends AbstractGatewayTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void the_context_starts() {
        assertThat(ctx).isNotNull();
    }

    @Test
    void the_stack_is_reactive_not_servlet() {
        // spring-boot-starter-web en el classpath rompe WebFlux: dos stacks
        // compitiendo por el mismo puerto y el Gateway no arranca.
        assertThat(ctx.getBeanNamesForType(
                org.springframework.web.reactive.DispatcherHandler.class)).isNotEmpty();
        assertThat(ctx.containsBean("dispatcherServlet")).isFalse();
    }

    @Test
    void el_cliente_de_Redis_es_REACTIVO() {
        // Un cliente bloqueante en el filtro de Security bloquea el event loop
        // de Netty y tira el throughput del proceso entero.
        assertThat(ctx.getBeanNamesForType(
                org.springframework.data.redis.core.ReactiveStringRedisTemplate.class)).isNotEmpty();
    }
}
