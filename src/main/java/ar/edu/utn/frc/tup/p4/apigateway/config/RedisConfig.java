package ar.edu.utn.frc.tup.p4.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Explicit Redis configuration. Spring Boot auto-provides the
 * {@link ReactiveStringRedisTemplate}; what is done here is to make sure the
 * serialization is ALWAYS {@link StringRedisSerializer} (the autoconfig does
 * it by default, but relying on that is fragile: it changes between
 * versions) and to leave visible in a single place the name of the key the
 * Gateway reads against Redis ({@code session:{userId}}).
 *
 * <p>The host/port/timeout/pool properties live in {@code application.yml}
 * (maintained by Base) under {@code spring.data.redis.*}; this batch does NOT
 * touch them because the current configuration already meets DEC-42
 * (timeout 500ms + pool 16/8/2).
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
