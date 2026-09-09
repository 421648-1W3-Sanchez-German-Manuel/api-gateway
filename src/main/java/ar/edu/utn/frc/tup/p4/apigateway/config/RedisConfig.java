package ar.edu.utn.frc.tup.p4.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configuracion explicita de Redis. Spring Boot autoprovee el
 * {@link ReactiveStringRedisTemplate}; lo que aca se hace es asegurar que la
 * serializacion sea SIEMPRE {@link StringRedisSerializer} (la autoconfig
 * lo hace por defecto, pero atarse a eso es fragil: cambia entre versiones)
 * y dejar visible en un solo lugar el nombre de la key que el Gateway lee
 * contra Redis ({@code session:{userId}}).
 *
 * <p>Las properties de host/port/timeout/pool viven en {@code application.yml}
 * (lo mantiene Base) bajo {@code spring.data.redis.*}; este lote NO las
 * toca porque la configuracion actual ya cumple DEC-42 (timeout 500ms +
 * pool 16/8/2).
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory cf) {
        return new ReactiveStringRedisTemplate(cf, context().build());
    }

    private RedisSerializationContext.RedisSerializationContextBuilder<String, String> context() {
        return RedisSerializationContext.<String, String>newSerializationContext(
                StringRedisSerializer.UTF_8);
    }
}