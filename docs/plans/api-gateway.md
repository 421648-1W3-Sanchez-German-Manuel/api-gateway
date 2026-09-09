# api-gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir el API Gateway de la plataforma: única puerta de entrada, autentica todo request contra el JWKS de `users-service`, rutea dinámicamente contra una allowlist explícita de Eureka, y entrega al destino un set de headers de identidad ya validados.

**Architecture:** Spring Cloud Gateway sobre **WebFlux** (el otro servicio es servlet: son stacks distintos a propósito). Un pipeline de **9 pasos** — la cadena de Spring Security más 8 `GlobalFilter` ordenados. El Gateway **autentica, rutea y propaga; no autoriza por role**: esa decisión vive en el `@PreAuthorize` del microservicio destino. Es el **único** servicio con `fetch-registry: true`, y la única infraestructura de estado que toca es una lectura de Redis para la sesión única.

**Tech Stack:** Java 21 · Maven · Spring Boot 4.1.1 · Spring Cloud 2025.1.3 "Oakwood" · `spring-cloud-starter-gateway-server-webflux` · Resilience4j · Redis (solo lectura) · Micrometer/OTel · Testcontainers + MockWebServer

**Spec:** `spec/SPEC-api-gateway.md` (documento par: `spec/SPEC-users-service.md`)

---

## Global Constraints

- **Java 21**, **Maven**, **Spring Boot 4.1.1**, **Spring Cloud 2025.1.3** (`DEC-35`, verificado contra la matriz oficial). BOM de Boot **primero**, Cloud después.
- **Group:** `ar.edu.utn.frc.tup.p4` · **Artifact:** `api-gateway` · **Package raíz:** `ar.edu.utn.frc.tup.p4.apigateway`.
- **Puertos** (`DEC-28`): aplicación **8080** (el **único** publicado de todo el compose), management **8081**. `users-service` está en **8082**.
- **Starter correcto:** `spring-cloud-starter-gateway-server-webflux`. Las properties viven bajo `spring.cloud.gateway.server.webflux.*` — el prefijo legacy `spring.cloud.gateway.*` está deprecado.
- 🔴 **R3 · El Gateway NO autoriza por role.** Cero `hasRole` / `hasAuthority` / comparaciones contra `ADMIN` o `MS` en toda la configuración de Security. Todo lo privado es `authenticated()`. (Criterio de DoD #11.)
- **R7 · El path NO se reescribe.** El locator genera `Path=/api/{nombre}/**` y `filters: []`. Nada de `RewritePath`.
- **R9 · Es el único servicio con `fetch-registry: true`.**
- **Pipeline de 9 pasos**, orden no negociable: Security → `CorrelationIdFilter@1` → `LoggingFilter@2` → `PublicRouteGuard@3` → `PrivateRouteGuard@4` → `AccountStateGuard@5` → `ServiceAudienceFilter@6` → `IdentityPropagationFilter@7` → `RateLimitFilter@8`.
- **Headers de identidad** (`DEC-05`): separador **coma sin espacio**, sin values vacíos, sin coma final; `MS` **dentro** de `X-Service-Scopes`, primero los roles y después los scopes.
- **Claims validados** (`DEC-44`): `iss` = `"users-service"` **siempre**, sin flag. `est`/`pwd`/`onb` obligatorios en el token de persona. Un rechazo loguea `JWT_RECHAZADO` **nombrando el claim**; el body del `401` no lo dice.
- **`DEC-01` fail-closed distinguiendo cause:** Redis no responde → **503 + `Retry-After`**; key ausente → **401 sesión cerrada**; key distinta → **401 sesión superada**. Nunca fail-open.
- **Nunca loguea:** bodies, tokens, header `Authorization`, ni el `clientSecret` de `/auth/token`.
- **Errores:** siempre `ProblemDetail` (RFC 9457), con los mismos `type` que `users-service` — en particular `https://tpi.utn.frc/errors/too-many-attempts` para el `429` (`DEC-24`).

---

## File Structure

```
api-gateway/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/ar/edu/utn/frc/tup/p4/apigateway/
    │   ├── ApiGatewayApplication.java
    │   ├── config/
    │   │   ├── SecurityConfig.java           # cadena reactiva + ReactiveJwtDecoder
    │   │   ├── DiscoveryLocatorConfig.java   # allowlist tipada + validación de arranque
    │   │   ├── ResilienceConfig.java
    │   │   ├── RedisConfig.java
    │   │   └── properties/{GatewayRoutingProperties,IdentityHeaderProperties,
    │   │                   RateLimitProperties,SessionCacheProperties,JwtProperties}.java
    │   ├── security/{SessionValidator,IssuerValidator,PrincipalContext,
    │   │             PrincipalContextFactory}.java
    │   ├── filters/{CorrelationIdFilter,LoggingFilter,PublicRouteGuard,PrivateRouteGuard,
    │   │            AccountStateGuard,ServiceAudienceFilter,IdentityPropagationFilter,
    │   │            RateLimitFilter}.java
    │   ├── ratelimit/{RateLimitKeyResolver,TokenBucket}.java + impl/
    │   ├── repository/SessionRepository.java + impl/{RedisSessionRepository,
    │   │              CachingSessionRepository}.java
    │   ├── web/{FallbackController,GatewayErrorAttributes,ProblemDetails}.java
    │   └── constants/{IdentityHeaders,PrincipalType,ErrorTypes}.java
    └── resources/{application.yml, logback-spring.xml}
```

**Criterio de decomposición:** un archivo por **paso del pipeline**. Cada filtro tiene una responsabilidad, un `@Order` y un test unitario propio; el orden efectivo entre ellos lo verifica `PipelineOrderIT`, no la lectura del código.

---

### Task 1: Scaffolding y base de tests

**Files:**
- Create: `pom.xml`, `src/main/java/…/ApiGatewayApplication.java`, `src/main/resources/application.yml`, `src/main/resources/logback-spring.xml`
- Create: `src/test/java/…/support/{AbstractGatewayTest,TokenFactory}.java`
- Test: `src/test/java/…/ApiGatewayApplicationTest.java`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `AbstractGatewayTest` — levanta el Gateway con puerto aleatorio, un **MockWebServer que sirve el JWKS** y otro que hace de microservicio destino, más Testcontainers Redis.
  - `TokenFactory` — **firma tokens de prueba con la misma clave que sirve el JWKS falso**: `personaValida(UUID)`, `persona(Consumer<JWTClaimsSet.Builder>)`, `servicio(String aud, String scope)`.

- [ ] **Step 1: Escribir el `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>ar.edu.utn.frc.tup.p4</groupId>
  <artifactId>api-gateway</artifactId>
  <version>0.0.1-SNAPSHOT</version>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <spring-boot.version>4.1.1</spring-boot.version>
    <spring-cloud.version>2025.1.3</spring-cloud.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- ORDEN OBLIGATORIO: Boot PRIMERO (DEC-35). -->
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version><type>pom</type><scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version><type>pom</type><scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- El starter RENOMBRADO. El viejo spring-cloud-starter-gateway sigue
         existiendo pero apunta al modulo legacy. -->
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    <!-- REACTIVE: un cliente Redis bloqueante en un filtro de WebFlux bloquea
         el event loop de Netty y tira el throughput del proceso entero. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
    </dependency>
    <dependency>
      <groupId>com.github.ben-manes.caffeine</groupId>
      <artifactId>caffeine</artifactId>
    </dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-tracing-bridge-otel</artifactId></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>io.projectreactor</groupId><artifactId>reactor-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.squareup.okhttp3</groupId><artifactId>mockwebserver</artifactId><version>4.12.0</version><scope>test</scope></dependency>
    <dependency><groupId>com.nimbusds</groupId><artifactId>nimbus-jose-jwt</artifactId><version>10.0.1</version><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.redis</groupId><artifactId>testcontainers-redis</artifactId><version>2.2.4</version><scope>test</scope></dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
      </plugin>
    </plugins>
  </build>
</project>
```

**NO incluir** (lo dice la spec §3): `spring-boot-starter-web` (rompe WebFlux: dos stacks en el mismo classpath), `spring-boot-starter-data-jpa` ni driver de MySQL (el Gateway no tiene base), `spring-boot-starter-oauth2-authorization-server` (la emisión es 100 % de `users-service`), `spring-kafka` (R8: lo asincrónico no pasa por el Gateway).

- [ ] **Step 2: Escribir la clase principal**

```java
package ar.edu.utn.frc.tup.p4.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@ConfigurationPropertiesScan
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

- [ ] **Step 3: Escribir `application.yml`**

```yaml
spring:
  application:
    name: api-gateway

  cloud:
    gateway:
      server:
        webflux:
          discovery:
            locator:
              enabled: true
              lower-case-service-id: true
              # ALLOWLIST (DEC-06). Mantener sincronizada con gateway.routing.allowlist:
              # this one is read by the locator, the other by the guards and tests.
              include-expression: >
                {'users-service'}.contains(serviceId.toLowerCase())
              predicates:
                - name: Path
                  args:
                    pattern: >
                      '/api/' + serviceId.toLowerCase().replace('-service','') + '/**'
              # FILTERS vacío A PROPÓSITO: el path NO se reescribe (R7).
              # NO agregar RewritePath acá.
              filters: []

          # DEC-27 - the only static route besides the fallback. The locator only
          # generates Path=/api/{name}/**; without this the JWKS passes Security
          # guards y muere en un 404 del propio Gateway.
          routes:
            - id: jwks
              uri: lb://users-service
              predicates:
                - Path=/.well-known/jwks.json
              filters: []

          # Defensa en profundidad: IdentityPropagationFilter los borra igual.
          # ⚠ traceparent NO va acá: se acepta el entrante (W3C).
          remove-request-headers:
            - X-Principal-Type
            - X-User-Id
            - X-Service-Id
            - X-User-Roles
            - X-Service-Scopes

  security:
    oauth2:
      resourceserver:
        jwt:
          # It points DIRECTLY at users-service: the gateway cannot route through
          # itself to fetch the key it validates with. DEC-28: 8082.
          jwk-set-uri: ${JWKS_URI:http://users-service:8082/.well-known/jwks.json}
          jws-algorithms: RS256      # único algoritmo admitido

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 500ms
      lettuce:
        pool: {max-active: 16, max-idle: 8, min-idle: 2}   # DEC-42

gateway:
  jwt:
    expected-issuer: users-service      # DEC-07 · se valida siempre (DEC-44)

  session-cache:                        # DEC-25
    ttl: 3s
    max-size: 10000

  routing:
    # Espejo tipado de la allowlist del include-expression.
    allowlist:
      - users-service
    service-id-suffix: "-service"
    path-prefix: "/api"

  identity:
    reserved-headers:
      - X-Principal-Type
      - X-User-Id
      - X-Service-Id
      - X-User-Roles
      - X-Service-Scopes

  rate-limit:
    enabled: true
    # DEC-24 · la ruta de auth SÍ es cara y su bucket va por IP.
    # Without this entry DoD criterion 7f cannot pass (the filter is a no-op).
    expensive-routes:
      - path: /api/users/public/auth/**
        key: IP
        capacity: 30              # DEC-42 · orden de magnitud, sin calibrar
        refill-per-minute: 30
    trusted-proxies:              # DEC-24 · para resolver X-Forwarded-For
      - 127.0.0.1/32
      - 10.0.0.0/8
      - 172.16.0.0/12
      - 192.168.0.0/16

resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
  timelimiter:
    configs:
      default:
        timeoutDuration: 3s       # DEC-42 · MENOR que el timeout del cliente
  bulkhead:
    configs:
      default:
        maxConcurrentCalls: 64

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
    register-with-eureka: true
    fetch-registry: true          # R9 · el ÚNICO servicio con true
    healthcheck:
      enabled: true
  instance:
    prefer-ip-address: true

server:
  port: ${SERVER_PORT:8080}       # DEC-28 · el único puerto publicado

management:
  server:
    port: ${MANAGEMENT_PORT:8081}
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,gateway
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
```

- [ ] **Step 4: Escribir `logback-spring.xml`**

Criterio de DoD #9: **el trace id sale en cada línea sin que ningún filtro lo loguee a mano.**

```xml
<configuration>
  <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
  <appender name="CONSOLA" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %5p [${spring.application.name:-},%X{traceId:-},%X{spanId:-},%X{requestId:-}] %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="CONSOLA"/></root>
</configuration>
```

- [ ] **Step 5: Escribir `TokenFactory`**

Sin esto no se puede probar nada: hay que **firmar tokens con la misma clave que sirve el JWKS falso**.

```java
package ar.edu.utn.frc.tup.p4.apigateway.support;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Signs test tokens with the key AbstractGatewayTest publishes as JWKS. */
public final class TokenFactory {

    public static final String KID = "test-kid";
    private static final RSAKey KEY = generate();

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID(KID).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) { throw new IllegalStateException(e); }
    }

    /** What the JWKS MockWebServer serves. The public half only. */
    public static String jwksJson() {
        return new JWKSet(KEY).toPublicJWKSet().toString();
    }

    /** A person token carrying ALL the mandatory claims (DEC-44). */
    public static String persona(UUID sub, String sid) {
        return persona(sub, sid, c -> { });
    }

    public static String persona(UUID sub, String sid, Consumer<JWTClaimsSet.Builder> ajuste) {
        JWTClaimsSet.Builder b = base()
                .subject(sub.toString())
                .claim("roles", List.of("STUDENT"))
                .claim("type", "user")
                .claim("sid", sid)
                .claim("est", "ACTIVE")
                .claim("pwd", false)
                .claim("onb", false);
        ajuste.accept(b);
        return sign(b.build());
    }

    public static String servicio(String clientId, String aud, String scope) {
        return servicio(clientId, aud, scope, c -> { });
    }

    public static String servicio(String clientId, String aud, String scope,
                                  Consumer<JWTClaimsSet.Builder> ajuste) {
        JWTClaimsSet.Builder b = base()
                .subject(clientId)
                .claim("roles", List.of("MS"))
                .claim("type", "service")
                .claim("scope", scope);
        if (aud != null) b.audience(aud);
        ajuste.accept(b);
        return sign(b.build());
    }

    private static JWTClaimsSet.Builder base() {
        Instant ahora = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("users-service")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(600)));
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
            jwt.sign(new RSASSASigner(KEY.toPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) { throw new IllegalStateException(e); }
    }

    private TokenFactory() { }
}
```

- [ ] **Step 6: Escribir `AbstractGatewayTest`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.support;

import com.redis.testcontainers.RedisContainer;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

/**
 * Base class for the integration tests. It starts:
 *  - a MockWebServer serving the JWKS with TokenFactory's key;
 *  - a MockWebServer standing in for the destination service that RECORDS what
 *    it receives, so the injected headers can be asserted on;
 *  - a real Redis, because single session and DEC-01 cannot be faked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractGatewayTest {

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    protected static MockWebServer jwks;
    protected static MockWebServer destino;

    @Autowired
    protected WebTestClient cliente;

    @BeforeAll
    static void arrancarMocks() throws IOException {
        jwks = new MockWebServer();
        jwks.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(TokenFactory.jwksJson());
            }
        });
        jwks.start();

        destino = new MockWebServer();
        destino.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });
        destino.start();
    }

    @AfterAll
    static void apagarMocks() throws IOException {
        jwks.shutdown();
        destino.shutdown();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> jwks.url("/.well-known/jwks.json").toString());
        // A static route to the MockWebServer: the tests do not need Eureka.
        r.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "users-test");
        r.add("spring.cloud.gateway.server.webflux.routes[1].uri",
                () -> "http://localhost:" + destino.getPort());
        r.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]",
                () -> "Path=/api/users/**");
        r.add("eureka.client.enabled", () -> "false");
    }

    /** Consumes and returns the last request that reached the destination. */
    protected RecordedRequest ultimoRequestAlDestino() throws InterruptedException {
        return destino.takeRequest();
    }
}
```

- [ ] **Step 7: Escribir el test de arranque**

```java
package ar.edu.utn.frc.tup.p4.apigateway;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayApplicationTest extends AbstractGatewayTest {

    @Autowired ApplicationContext ctx;

    @Test
    void the_context_starts() { assertThat(ctx).isNotNull(); }

    @Test
    void the_stack_is_reactive_not_servlet() {
        // spring-boot-starter-web on the classpath breaks WebFlux: two stacks
        // competing for the same port, and the gateway does not start.
        assertThat(ctx.getBeanNamesForType(
                org.springframework.web.reactive.DispatcherHandler.class)).isNotEmpty();
        assertThat(ctx.containsBean("dispatcherServlet")).isFalse();
    }

    @Test
    void el_cliente_de_Redis_es_REACTIVO() {
        // A blocking client inside the Security filter blocks Netty's event
        // loop and drags down the throughput of the whole process.
        assertThat(ctx.getBeanNamesForType(
                org.springframework.data.redis.core.ReactiveStringRedisTemplate.class)).isNotEmpty();
    }
}
```

- [ ] **Step 8: Correr y verify**

Run: `mvn -q test -Dtest=ApiGatewayApplicationTest`
Expected: PASS — 3 tests.
Run: `mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot-dependencies`
Expected: `4.1.1`. Si es menor, los BOM están en el orden equivocado.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "chore: scaffolding del api-gateway sobre WebFlux

Starter renombrado (spring-cloud-starter-gateway-server-webflux) y properties
bajo spring.cloud.gateway.server.webflux.*.
TokenFactory firma tokens de test con la misma key del JWKS falso: sin eso
no se puede probar nada del pipeline."
```

---

### Task 2: Constantes, tipos de identidad y properties

**Files:**
- Create: `src/main/java/…/constants/{IdentityHeaders,PrincipalType,ErrorTypes}.java`
- Create: `src/main/java/…/security/{PrincipalContext,PrincipalContextFactory}.java`
- Create: `src/main/java/…/config/properties/{GatewayRoutingProperties,IdentityHeaderProperties,RateLimitProperties,SessionCacheProperties,JwtProperties}.java`
- Test: `src/test/java/…/security/PrincipalContextFactoryTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `PrincipalContext` — `record PrincipalContext(PrincipalType type, String subject, List<String> roles, List<String> scopes, String sid, String est, Boolean pwd, Boolean onb, String onBehalfOf)` con `static PrincipalContext from(Jwt)`; `IdentityHeaders.RESERVED` (`List<String>`).

- [ ] **Step 1: Escribir el test (falla)**

`DEC-05` es un contrato de serialización: hay que probarlo, no confiarlo.

```java
package ar.edu.utn.frc.tup.p4.apigateway.security;

import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrincipalContextFactoryTest {

    private Jwt.Builder base() {
        return Jwt.withTokenValue("x").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("iss", "users-service");
    }

    @Test
    void a_person_token_is_mapped_in_full() {
        UUID sub = UUID.randomUUID();
        Jwt jwt = base().subject(sub.toString()).claim("type", "user")
                .claim("roles", List.of("STUDENT", "PROFESSOR")).claim("sid", "sid-1")
                .claim("est", "ACTIVE").claim("pwd", false).claim("onb", false).build();

        PrincipalContext p = PrincipalContext.from(jwt);

        assertThat(p.type()).isEqualTo(PrincipalType.USER);
        assertThat(p.subject()).isEqualTo(sub.toString());
        assertThat(p.roles()).containsExactly("STUDENT", "PROFESSOR");
        assertThat(p.sid()).isEqualTo("sid-1");
        assertThat(p.est()).isEqualTo("ACTIVE");
    }

    @Test
    void serializa_los_roles_con_coma_SIN_espacio() {
        // DEC-05: on the destination side a single split(",") is enough.
        Jwt jwt = base().subject("s").claim("type", "user")
                .claim("roles", List.of("STUDENT", "PROFESSOR")).claim("sid", "s")
                .claim("est", "ACTIVE").claim("pwd", false).claim("onb", false).build();

        assertThat(PrincipalContext.from(jwt).rolesHeader()).isEqualTo("STUDENT,PROFESSOR");
    }

    @Test
    void el_header_de_servicio_lleva_MS_PRIMERO_y_despues_los_scopes() {
        // DEC-05: "MS + the token's scope", in that order, stable.
        Jwt jwt = base().subject("cursos-service").claim("type", "service")
                .claim("roles", List.of("MS")).claim("aud", List.of("users-service"))
                .claim("scope", "users.profile.read").build();

        assertThat(PrincipalContext.from(jwt).scopesHeader()).isEqualTo("MS,users.profile.read");
    }

    @Test
    void emits_no_empty_values_no_trailing_comma_and_no_duplicates() {
        Jwt jwt = base().subject("cursos-service").claim("type", "service")
                .claim("roles", List.of("MS")).claim("aud", List.of("users-service"))
                .claim("scope", "users.profile.read  users.profile.read ").build();

        String header = PrincipalContext.from(jwt).scopesHeader();
        assertThat(header).isEqualTo("MS,users.profile.read");
        assertThat(header).doesNotEndWith(",").doesNotContain(",,").doesNotContain(", ");
    }

    @Test
    void an_unknown_type_is_rejected() {
        Jwt jwt = base().subject("s").claim("type", "robot").build();
        assertThatThrownBy(() -> PrincipalContext.from(jwt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_service_token_carries_neither_sid_nor_est() {
        Jwt jwt = base().subject("cursos-service").claim("type", "service")
                .claim("roles", List.of("MS")).claim("aud", List.of("users-service"))
                .claim("scope", "users.profile.read").build();

        PrincipalContext p = PrincipalContext.from(jwt);
        assertThat(p.sid()).isNull();
        assertThat(p.est()).isNull();
    }
}
```

- [ ] **Step 2: Correr y verify que falla**

Run: `mvn -q test -Dtest=PrincipalContextFactoryTest`
Expected: FAIL — faltan las clases.

- [ ] **Step 3: Escribir las constantes**

```java
package ar.edu.utn.frc.tup.p4.apigateway.constants;

import java.util.List;

public final class IdentityHeaders {
    public static final String PRINCIPAL_TYPE = "X-Principal-Type";
    public static final String USER_ID        = "X-User-Id";
    public static final String USER_ROLES     = "X-User-Roles";
    public static final String SERVICE_ID     = "X-Service-Id";
    public static final String SERVICE_SCOPES = "X-Service-Scopes";
    public static final String REQUEST_ID     = "X-Request-Id";

    /** The five the gateway ALWAYS strips before injecting. Anti-spoofing.
     *  traceparent is NOT here: an incoming one is accepted (W3C Trace Context). */
    public static final List<String> RESERVED = List.of(
            PRINCIPAL_TYPE, USER_ID, USER_ROLES, SERVICE_ID, SERVICE_SCOPES);

    private IdentityHeaders() { }
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.constants;

public enum PrincipalType {
    USER("user"), SERVICE("service");

    private final String claim;
    PrincipalType(String claim) { this.claim = claim; }
    public String claim() { return claim; }

    public static PrincipalType from(String value) {
        for (PrincipalType t : values()) if (t.claim.equals(value)) return t;
        throw new IllegalArgumentException("unknown token type: " + value);
    }
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.constants;

import java.net.URI;

/** The SAME types users-service returns: one single error namespace. */
public final class ErrorTypes {
    private static final String BASE = "https://tpi.utn.frc/errors/";

    public static final URI NOT_AUTHENTICATED     = URI.create(BASE + "not-authenticated");
    public static final URI SESSION_CLOSED     = URI.create(BASE + "session-closed");
    public static final URI SESSION_SUPERSEDED    = URI.create(BASE + "session-superseded");
    public static final URI INVALID_AUDIENCE = URI.create(BASE + "invalid-audience");
    public static final URI PENDING_ACCOUNT   = URI.create(BASE + "pending-account");
    public static final URI PASSWORD_CHANGE_REQUIRED = URI.create(BASE + "password-change-required");
    public static final URI ONBOARDING_PENDING      = URI.create(BASE + "onboarding-pending");
    public static final URI SERVICE_UNAVAILABLE    = URI.create(BASE + "service-unavailable");
    public static final URI ROUTE_NOT_FOUND          = URI.create(BASE + "route-not-found");
    /** DEC-24 - the SAME one auth/ uses for its per-e-mail limit. */
    public static final URI TOO_MANY_ATTEMPTS       = URI.create(BASE + "too-many-attempts");

    private ErrorTypes() { }
}
```

- [ ] **Step 4: Escribir `PrincipalContext`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.security;

import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;
import java.util.stream.Stream;

/**
 * The ALREADY VALIDATED identity, in an immutable record. Everything that goes
 * out as a header derives from here and only here, never from an inbound one.
 */
public record PrincipalContext(PrincipalType type, String subject, List<String> roles,
                               List<String> scopes, String sid, String est,
                               Boolean pwd, Boolean onb, String onBehalfOf) {

    public static PrincipalContext from(Jwt jwt) {
        PrincipalType type = PrincipalType.from(jwt.getClaimAsString("type"));
        List<String> roles = normalize(jwt.getClaimAsStringList("roles"));

        if (type == PrincipalType.USER) {
            return new PrincipalContext(type, jwt.getSubject(), roles, List.of(),
                    jwt.getClaimAsString("sid"), jwt.getClaimAsString("est"),
                    jwt.getClaim("pwd"), jwt.getClaim("onb"), null);
        }
        List<String> scopes = normalize(
                Arrays.asList(Optional.ofNullable(jwt.getClaimAsString("scope"))
                        .orElse("").split("[\\s,]+")));
        return new PrincipalContext(type, jwt.getSubject(), roles, scopes,
                null, null, null, null, jwt.getClaimAsString("on_behalf_of"));
    }

    /** DEC-05: comma with no space, no empties, no duplicates, no trailing comma. */
    public String rolesHeader() { return String.join(",", roles); }

    /** DEC-05: roles FIRST (MS), then the scopes. Stable order. */
    public String scopesHeader() {
        return Stream.concat(roles.stream(), scopes.stream()).distinct()
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(v -> !v.isEmpty()).distinct().toList();
    }
}
```

- [ ] **Step 5: Escribir las properties**

```java
package ar.edu.utn.frc.tup.p4.apigateway.config.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "gateway.routing")
public record GatewayRoutingProperties(@NotEmpty List<String> allowlist,
                                       String serviceIdSuffix, String pathPrefix) {

    /** users-service -> /api/users. The derivation from §5.2, in one place. */
    public String serviceIdToPathSegment(String serviceId) {
        return serviceId.toLowerCase(Locale.ROOT).replace(serviceIdSuffix, "");
    }

    /** "users" -> users-service. The inverse of the previous one. */
    public String pathSegmentToServiceId(String segment) {
        return segment.toLowerCase(Locale.ROOT) + serviceIdSuffix;
    }

    /** true when the string already is a serviceId (users-service), not a bare host. */
    public boolean looksLikeServiceId(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).endsWith(serviceIdSuffix);
    }

    /**
     * The destination serviceId of a path /api/{name}/..., or null when the path
     * does not follow the convention. Used by ServiceAudienceFilter (T8) and the guards.
     */
    public String serviceIdFromPath(String path) {
        String[] parts = path.split("/");
        // ["", "api", "users", ...]
        if (parts.length < 3 || !pathPrefix.equals("/" + parts[1])) {
            return null;
        }
        return pathSegmentToServiceId(parts[2]);
    }
}
```
```java
@ConfigurationProperties(prefix = "gateway.identity")
public record IdentityHeaderProperties(List<String> reservedHeaders) { }
```
```java
@ConfigurationProperties(prefix = "gateway.session-cache")
public record SessionCacheProperties(java.time.Duration ttl, long maxSize) { }
```
```java
@ConfigurationProperties(prefix = "gateway.jwt")
public record JwtProperties(String expectedIssuer) { }
```
```java
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(boolean enabled, List<RutaCara> expensiveRoutes,
                                  List<String> trustedProxies) {
    public record RutaCara(String path, String key, int capacity, int refillPerMinute) { }
}
```

- [ ] **Step 6: Correr y verify que pasa**

Run: `mvn -q test -Dtest=PrincipalContextFactoryTest`
Expected: PASS — 6 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: PrincipalContext y contrato de serializacion de headers (DEC-05)

Coma sin espacio, MS primero, sin vacios ni duplicados. Es un contrato con
los otros once micros: se test, no se confia."
```

---
### Task 3: Ruteo dinámico gobernado por allowlist

**Files:**
- Create: `src/main/java/…/config/AllowlistRouteLocator.java`
- Create: `src/main/java/…/config/DiscoveryLocatorConfig.java`
- Test: `src/test/java/…/integration/DiscoveryAllowlistIT.java`

**Interfaces:**
- Consumes: `GatewayRoutingProperties` (T2).
- Produces: las `RouteDefinition` dinámicas, y el bean `serviceIdToPathSegment` reusable por `PublicRouteGuard` (T5) y los tests.

> ### 🔴 Las rutas se generan en Java, NO con el DiscoveryClient locator
>
> El locator de Spring Cloud Gateway evalúa `include-expression` y `predicates`
> con `SimpleEvaluationContext.forReadOnlyDataBinding().build()` — es decir
> **sin `withInstanceMethods()`: ninguna llamada a método está permitida en esas
> expresiones**. Una expresión como `serviceId.toLowerCase()` o
> `.replace('-service','')` falla siempre con:
>
> ```
> EL1004E: Method call: Method toLowerCase() cannot be found on type String
> ```
>
> Y acá está lo que hace que este error sea tan caro: **la excepción se tira
> dentro del listener de refresco de rutas, así que no rompe el arranque**. El
> Gateway levanta sano, sin un stack trace a la vista, con la tabla de rutas
> **vacía**, y contesta **404 a todo**. El síntoma no se parece en nada a la
> cause, y se busca el problema en el ruteo, en Eureka o en la allowlist.
>
> Por eso el locator dinámico va **apagado** (`discovery.locator.enabled:
> false`) y las rutas las genera `AllowlistRouteLocator`, un
> `RouteDefinitionLocator` en Java que lee la lista tipada.
>
> Dos cosas que se ganan de paso:
>
> - **Desaparece la allowlist duplicada.** `gateway.routing.allowlist` pasa a
>   ser la única fuente, la misma que ya usan los guards y el
>   `ServiceAudienceFilter`. No hay dos listas que mantener sincronizadas.
> - **Un servicio caído devuelve 503, no 404.** Como la ruta no depende del
>   registro de Eureka, existe siempre: si el micro no está, el load balancer no
>   encuentra instancia y responde el fallback del circuit breaker. Un 404 diría
>   "este endpoint no existe", que es falso y manda a find el error donde no
>   está. Además elimina la carrera de arranque: un micro que se registra
>   después del Gateway ya no queda sin ruta hasta el refresco.

- [ ] **Step 1: Escribir el test (falla)** — criterios de DoD #2 y #3

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.config.DiscoveryLocatorConfig;
import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryAllowlistIT extends AbstractGatewayTest {

    @Test
    void el_path_llega_AL_DESTINO_SIN_REESCRIBIR() throws Exception {
        // R7 - DoD criterion #2. The destination receives /api/users/me, not /me.
        // If someone adds a RewritePath "to clean up the prefix", this test
        // catches it: the destination controllers are mapped with the prefix.
        UUID sub = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(sub, "sid-1"))
                .exchange();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getPath()).isEqualTo("/api/users/me");
    }

    @Test
    void un_servicio_FUERA_de_la_allowlist_responde_404() {
        // DoD criterion #3. Registering in Eureka does NOT expose a service:
        // until it is on the allowlist, it does not exist to the outside world.
        cliente.get().uri("/api/otro/lo-que-sea")
                .header("Authorization", "Bearer " + TokenFactory.persona(UUID.randomUUID(), "s"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void la_derivacion_de_serviceId_a_segmento_es_la_de_la_spec() {
        var props = new GatewayRoutingProperties(List.of("users-service"), "-service", "/api");
        assertThat(props.serviceIdToPathSegment("users-service")).isEqualTo("users");
        assertThat(props.serviceIdToPathSegment("USERS-SERVICE")).isEqualTo("users");
        assertThat(props.serviceIdToPathSegment("cursos-service")).isEqualTo("cursos");
    }

    @Test
    void el_arranque_FALLA_si_la_allowlist_incluye_al_propio_gateway() {
        // Routing to itself makes an infinite loop that shows up as a stack
        // overflow or a timeout, never as a readable error.
        var props = new GatewayRoutingProperties(
                List.of("users-service", "api-gateway"), "-service", "/api");
        assertThatThrownBy(() -> new DiscoveryLocatorConfig(props).validateAllowlist())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-gateway");
    }

    @Test
    void el_arranque_FALLA_si_la_allowlist_incluye_a_eureka() {
        var props = new GatewayRoutingProperties(
                List.of("users-service", "eureka-server"), "-service", "/api");
        assertThatThrownBy(() -> new DiscoveryLocatorConfig(props).validateAllowlist())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eureka-server");
    }
}
```

- [ ] **Step 2: Correr y verify que falla**

Run: `mvn -q test -Dtest=DiscoveryAllowlistIT`
Expected: FAIL — falta `DiscoveryLocatorConfig`.

- [ ] **Step 3: Escribir la configuración**

```java
package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * It declares NO dynamic routes: the DiscoveryClient locator generates them
 * from the `include-expression` in application.yml. This class validates the
 * allowlist at startup and exposes the serviceId-to-path-segment derivation.
 *
 * The allowlist is DUPLICATED on purpose: `include-expression` (SpEL, read by
 * the locator) and `gateway.routing.allowlist` (typed, read by the guards and
 * the tests). There is no way for the locator to read a typed list, so the
 * price is keeping the two in sync - and startup complains if the
 * typed one is left empty.
 */
@Configuration
public class DiscoveryLocatorConfig {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryLocatorConfig.class);
    private static final List<String> PROHIBIDOS = List.of("api-gateway", "eureka-server");

    private final GatewayRoutingProperties props;

    public DiscoveryLocatorConfig(GatewayRoutingProperties props) { this.props = props; }

    @PostConstruct
    public void validateAllowlist() {
        for (String prohibido : PROHIBIDOS) {
            if (props.allowlist().stream()
                    .anyMatch(s -> s.toLowerCase(Locale.ROOT).equals(prohibido))) {
                throw new IllegalStateException(
                        "gateway.routing.allowlist no puede contener '" + prohibido + "': "
                        + "rutear la infraestructura a traves del Gateway produce un bucle.");
            }
        }
        log.info("Allowlist de ruteo: {}", props.allowlist());
    }
}
```

- [ ] **Step 4: Correr y verify que pasa**

Run: `mvn -q test -Dtest=DiscoveryAllowlistIT`
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: ruteo dinamico gobernado por allowlist con validation de arranque

R7: el path NO se reescribe, y hay test que lo afirma sobre lo que recibe el
destino. Registrarse en Eureka no expone un servicio: fuera de la allowlist, 404."
```

---

### Task 4: Errores uniformes y fallback

**Files:**
- Create: `src/main/java/…/web/{GatewayErrorAttributes,FallbackController}.java`
- Create: `src/main/java/…/web/RouteNotFoundHandler.java`

> **`ProblemDetails` ya está en la base**, con su test. Es la costura por la
> que salen TODOS los errores del Gateway, y tres lotes la consumen (T6, T8,
> T12): si naciera acá, esos tres no compilarían hasta que este lote mergee.
> Tu tarea la usa, no la escribe.

> **Todos los errores del Gateway son problem+json, incluido el 404.** Un
> prefijo que no está en la allowlist termina, si nadie lo intercepta, en el
> manejador de errores por defecto de WebFlux, que responde
> `{timestamp, path, status, error}` — un 404 correcto pero **sin `type`**. El
> contrato dice que el cliente ramifica por `type`, así que ese sería el único
> error del Gateway que no se puede clasificar. El Step 4 lo cubre.

**Interfaces:**
- Consumes: `ErrorTypes` (T2).
- Produces: `ProblemDetails.write(ServerWebExchange, HttpStatus, URI type, String title, String detail)` → `Mono<Void>` — **el único** camino por el que el Gateway corta un request; y `ProblemDetails.withRetryAfter(..., Duration)`.

- [ ] **Step 1: Escribir el test (falla)**

```java
package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsTest {

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/me").header("X-Request-Id", "req-1"));
    }

    @Test
    void escribe_un_ProblemDetail_con_content_type_RFC_9457() {
        var ex = exchange();
        StepVerifier.create(ProblemDetails.write(ex, HttpStatus.UNAUTHORIZED,
                ErrorTypes.SESSION_SUPERSEDED, "Sesion superada", "Otro dispositivo inicio sesion."))
                .verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getHeaders().getContentType().toString())
                .isEqualTo("application/problem+json");
    }

    @Test
    void el_429_lleva_Retry_After_y_el_type_compartido_con_users_service() {
        // DEC-24: the frontend has ONE handling branch and does not need to
        // know whether the gateway or auth/ answered.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.TOO_MANY_REQUESTS,
                ErrorTypes.TOO_MANY_ATTEMPTS, "Demasiados intentos",
                "Superó el limite.", Duration.ofSeconds(60))).verifyComplete();

        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(ErrorTypes.TOO_MANY_ATTEMPTS.toString()).endsWith("/too-many-attempts");
    }

    @Test
    void el_503_por_Redis_caido_lleva_Retry_After() {
        // DEC-01: fail-closed, but the client has to know that retrying helps
        // here, unlike a 401, where retrying fixes nothing.
        var ex = exchange();
        StepVerifier.create(ProblemDetails.withRetryAfter(ex, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorTypes.SERVICE_UNAVAILABLE, "No disponible",
                "Reintente en unos segundos.", Duration.ofSeconds(5))).verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }
}
```

- [ ] **Step 2: Escribir `ProblemDetails`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ONLY way the gateway cuts a request short. A filter that writes a
 * response by hand breaks the uniformity of the error contract.
 */
public final class ProblemDetails {

    /** Exchange attribute where a filter leaves extra keys for the body. */
    public static final String ATTR_EXTRAS = "gateway.problemExtras";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                      URI type, String title, String detail) {
        return write(exchange, status, type, title, detail, null);
    }

    public static Mono<Void> withRetryAfter(ServerWebExchange exchange, HttpStatus status,
                                           URI type, String title, String detail,
                                           Duration retryAfter) {
        return write(exchange, status, type, title, detail, retryAfter);
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                                       URI type, String title, String detail,
                                       Duration retryAfter) {
        ServerHttpResponse res = exchange.getResponse();
        res.setStatusCode(status);
        res.getHeaders().setContentType(PROBLEM_JSON);
        if (retryAfter != null) {
            res.getHeaders().add(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.toString());
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getPath().value());
        String requestId = exchange.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID);
        if (requestId != null) body.put("requestId", requestId);

        // Extra keys set by whoever cuts the request - AccountStateGuard adds
        // `accountStatus`, which is what the frontend uses to decide which
        // screen to show. Same criterion as users-service's ProblemDetail.
        Object extras = exchange.getAttribute(ATTR_EXTRAS);
        if (extras instanceof Map<?, ?> m) m.forEach((k, v) -> body.put(String.valueOf(k), v));

        try {
            DataBuffer buffer = res.bufferFactory().wrap(MAPPER.writeValueAsBytes(body));
            return res.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return res.setComplete();
        }
    }

    private ProblemDetails() { }
}
```

- [ ] **Step 3: Escribir el `FallbackController`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** The circuit breaker's destination: a 503 with the SAME shape as the rest. */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{serviceId}")
    public Mono<Void> fallback(@PathVariable String serviceId, ServerWebExchange exchange) {
        return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorTypes.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "El servicio '" + serviceId + "' no esta respondiendo. Reintente en unos segundos.",
                Duration.ofSeconds(10));
    }
}
```

- [ ] **Step 4: Escribir el handler del 404**

```java
package ar.edu.utn.frc.tup.p4.apigateway.web;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * A prefix that is not on the allowlist would land in WebFlux's default error
 * handler, which answers `{timestamp, path, status, error}`: a correct 404 that
 * is NOT problem+json and carries no `type`. The contract says every error is
 * RFC 9457 and that the client branches on `type`.
 *
 * It intercepts the 404 only: every other error passes through untouched to the
 * usual handler - in particular the circuit breaker's 503, which has its own
 * path through FallbackController. `@Order(-2)` puts it ahead of
 * DefaultErrorWebExceptionHandler, which sits at -1.
 */
@Component
@Order(-2)
public class RouteNotFoundHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!(ex instanceof ResponseStatusException rse)
                || rse.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
            return Mono.error(ex);
        }
        return ProblemDetails.write(exchange, HttpStatus.NOT_FOUND,
                ErrorTypes.ROUTE_NOT_FOUND, "Ruta inexistente",
                "La ruta solicitada no existe.");
    }
}
```

- [ ] **Step 5: Correr y verify que pasa**

Run: `mvn -q test -Dtest=ProblemDetailsTest`
Expected: PASS — 3 tests.

Con el stack levantado, el 404 tiene que traer `type`:

```bash
curl -s http://localhost:8080/api/nope/x -H "Authorization: Bearer $T"
# -> {"type":"https://tpi.utn.frc/errors/route-not-found", ...}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: ProblemDetail uniforme y fallback del breaker

Un solo camino para cortar un request. El 429 usa el mismo type que
users-service (DEC-24) y el 503 lleva Retry-After (DEC-01)."
```

---

### Task 5: Sesión única — repositorio Redis con caché local

**Files:**
- Create: `src/main/java/…/repository/impl/{RedisSessionRepository,CachingSessionRepository}.java`

> **La interfaz `SessionRepository` y su `SessionState` sellado ya están en la
> base.** Vos escribís las dos implementaciones. Igual que arriba: T6 compila
> contra la interfaz desde el día uno, y lo que espera de vos es el bean.
- Create: `src/main/java/…/config/RedisConfig.java`
- Test: `src/test/java/…/repository/CachingSessionRepositoryTest.java`

**Interfaces:**
- Consumes: `SessionCacheProperties` (T2).
- Produces: `SessionRepository.findSid(String userId)` → `Mono<SessionState>` donde `sealed interface SessionState` tiene `Active(String sid)`, `Absent`, `Unavailable` — **el tipo obliga a manejar las tres ramas de `DEC-01`**.

- [ ] **Step 1: Escribir la interfaz**

```java
package ar.edu.utn.frc.tup.p4.apigateway.repository;

import reactor.core.publisher.Mono;

public interface SessionRepository {

    /**
     * DEC-01 - the three branches are a TYPE, not an Optional plus a flag: the
     * compiler forces "no session" (401) to be told apart from "Redis is not
     * responding" (503). With Optional<String> both look the same - empty - and
     * that is exactly the confusion that produces an accidental fail-open.
     */
    sealed interface SessionState {
        record Active(String sid) implements SessionState { }
        record Absent() implements SessionState { }
        record Unavailable(Throwable cause) implements SessionState { }
    }

    Mono<SessionState> findSid(String userId);
}
```

- [ ] **Step 2: Escribir el test de la caché (falla)** — criterio de DoD #7g

```java
package ar.edu.utn.frc.tup.p4.apigateway.repository;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.SessionCacheProperties;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository.SessionState;
import ar.edu.utn.frc.tup.p4.apigateway.repository.impl.CachingSessionRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** DEC-25 - the risk v5 addresses is AVAILABILITY, not load. */
class CachingSessionRepositoryTest {

    private final SessionCacheProperties props = new SessionCacheProperties(Duration.ofSeconds(3), 100);

    @Test
    void dos_lecturas_seguidas_pegan_UNA_sola_vez_a_Redis() {
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet(); return Mono.just(new SessionState.Active("sid-1")); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(1);
    }

    @Test
    void NO_cachea_el_estado_NoDisponible() {
        // Caching a Redis failure for 3 s turns a hiccup into a guaranteed 3 s
        // outage. Only what could actually be read gets cached.
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet();
                        return Mono.just(new SessionState.Unavailable(new RuntimeException("caido"))); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(2);
    }

    @Test
    void cachea_tambien_el_estado_Ausente() {
        // A recent logout is a legitimate and frequent case: there is no reason
        // to hit Redis on every request of an already closed session.
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet(); return Mono.just(new SessionState.Absent()); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(1);
    }

    @Test
    void cada_usuario_tiene_su_propia_entrada() {
        AtomicInteger llamadas = new AtomicInteger();
        var cacheado = new CachingSessionRepository(
                id -> { llamadas.incrementAndGet(); return Mono.just(new SessionState.Active(id)); },
                props);

        StepVerifier.create(cacheado.findSid("u1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(cacheado.findSid("u2")).expectNextCount(1).verifyComplete();

        assertThat(llamadas.get()).isEqualTo(2);
    }
}
```

- [ ] **Step 3: Escribir las dos implementaciones**

```java
package ar.edu.utn.frc.tup.p4.apigateway.repository.impl;

import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * The ONLY Redis read in the whole gateway. It never writes, never deletes:
 * session:{userId} is written by users-service's login and deleted by its
 * logout and its deactivation (DEC-22).
 */
@Repository
public class RedisSessionRepository implements SessionRepository {

    private final ReactiveStringRedisTemplate redis;

    public RedisSessionRepository(ReactiveStringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Mono<SessionState> findSid(String userId) {
        return redis.opsForValue().get("session:" + userId)
                .map(sid -> (SessionState) new SessionState.Active(sid))
                .defaultIfEmpty(new SessionState.Absent())
                // DEC-01: a Redis error does NOT become "absent".
                // Confundirlos es fail-open disfrazado de fail-closed.
                .onErrorResume(e -> Mono.just(new SessionState.Unavailable(e)));
    }
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.repository.impl;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.SessionCacheProperties;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * DEC-25 - a PROCESS cache with a short TTL. It redefines "immediate
 * invalidation" from 0 s to <=3 s: a deliberate departure from the v5 text,
 * which said "without waiting the 10 min of exp".
 *
 * The risk it mitigates is NOT load: 120 concurrent users are ~1,200 GET/s
 * against a Redis that does ~100,000 ops/s. It is AVAILABILITY: with DEC-01
 * fail-closed, Redis caido era plataforma caida.
 */
@Repository
@Primary
public class CachingSessionRepository implements SessionRepository {

    private final SessionRepository delegate;
    private final Cache<String, SessionState> cache;

    public CachingSessionRepository(
            @Qualifier("redisSessionRepository") SessionRepository delegate,
            SessionCacheProperties props) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(props.ttl())
                .maximumSize(props.maxSize())
                .build();
    }

    @Override
    public Mono<SessionState> findSid(String userId) {
        SessionState cacheado = cache.getIfPresent(userId);
        if (cacheado != null) return Mono.just(cacheado);

        return delegate.findSid(userId).doOnNext(status -> {
            // Unavailable is NOT cached: caching a Redis failure for 3 s turns
            // a hiccup into a guaranteed 3 s outage.
            if (!(status instanceof SessionState.Unavailable)) cache.put(userId, status);
        });
    }
}
```

> Los dos beans implementan la misma interfaz: el caché es `@Primary` (lo inyecta `SessionValidator`) y su delegate va **calificado por nombre**, o Spring intentaría inyectarle el propio caché y el arranque fallaría con una dependencia circular.

- [ ] **Step 4: Correr y verify que pasa**

Run: `mvn -q test -Dtest=CachingSessionRepositoryTest`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: sesion unica con tipo sellado de tres ramas y cache de 3s

DEC-01: las tres ramas son un TIPO, no un Optional con flag — el compilador
obliga a distinguir 401 de 503. Con Optional las dos se ven igual: vacio.
DEC-25: Unavailable no se cachea, o un hipo de Redis seria una caida de 3s."
```

---

### Task 6: Autenticación — `SecurityConfig`, `iss` y `sid`

**Files:**
- Create: `src/main/java/…/security/{IssuerValidator,SessionValidator}.java`
- Create: `src/main/java/…/filters/SessionGuard.java`
- Create: `src/main/java/…/config/SecurityConfig.java`
- Test: `src/test/java/…/integration/{IssuerValidationIT,SessionInvalidationIT}.java`

**Interfaces:**
- Consumes: `SessionRepository` y `ProblemDetails` (**ambos en la base**, así
  que compilás desde el día uno), `JwtProperties` (T2).
- Espera de T5: el **bean** que implementa `SessionRepository`. Sin él la
  cadena de Security compila pero el contexto no levanta, así que tus dos IT
  corren recién cuando T5 esté en `main`. El resto de la tarea no espera.
- Produces: la cadena de Security. Después de este paso, `exchange.getPrincipal()` entrega un `JwtAuthenticationToken` ya validado.

- [ ] **Step 1: Escribir el test de `iss` (falla)** — criterio de DoD #7c

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * DEC-44 - validated ALWAYS, with no flag. The safety net is
 * TokenContractTest on the users-service side, plus a 401 that says WHICH claim
 * was missing - that turns "everything returns 401 and I do not know why" into
 * de grep.
 */
class IssuerValidationIT extends AbstractGatewayTest {

    @Test
    void un_token_con_iss_distinto_es_rechazado() {
        String malo = TokenFactory.persona(UUID.randomUUID(), "sid-1",
                b -> b.issuer("otro-emisor"));
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + malo)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void un_token_SIN_iss_es_rechazado() {
        // This is exactly the token an attacker would forge: that is why there
        // validador tolerante que acepte "ausente o correcto".
        String sinIss = TokenFactory.persona(UUID.randomUUID(), "sid-1", b -> b.issuer(null));
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + sinIss)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void un_token_con_algoritmo_distinto_de_RS256_es_rechazado() {
        // "none" included. jws-algorithms: RS256 in the yml covers it.
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.hs256Falso())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void el_cuerpo_del_401_NO_dice_que_claim_falto() {
        // An attacker is not told what the token was missing: that goes to the log.
        String malo = TokenFactory.persona(UUID.randomUUID(), "s", b -> b.issuer("otro"));
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + malo)
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").value(d ->
                        org.assertj.core.api.Assertions.assertThat((String) d)
                                .doesNotContain("iss"));
    }
}
```

Agregar a `TokenFactory` un `hs256Falso()` que firme con `MACSigner` y una clave simétrica de 32 bytes.

- [ ] **Step 2: Escribir el test de sesión (falla)** — criterios de DoD #7 y #7b

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

class SessionInvalidationIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @Test
    void un_sid_desactualizado_da_401_SESION_SUPERADA() {
        // DoD criterion #7. Valid signature and exp, but another device won.
        UUID u = UUID.randomUUID();
        redis.opsForValue().set("session:" + u, "sid-B").block();

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-A"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/session-superseded"));
    }

    @Test
    void la_key_AUSENTE_da_401_SESION_CERRADA_no_503() {
        // DEC-01: tell the cause apart. Absent is a logout, not an outage.
        UUID u = UUID.randomUUID();
        redis.delete("session:" + u).block();

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-A"))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/session-closed"));
    }

    @Test
    void con_Redis_DETENIDO_responde_503_con_Retry_After_nunca_401_ni_200() {
        // DoD criterion #7b. It is the difference between "your session expired"
        // (a lie, and the user signs in again for nothing) and "come back later".
        UUID u = UUID.randomUUID();
        redis.opsForValue().set("session:" + u, "sid-A").block();
        String token = TokenFactory.persona(u, "sid-A");

        REDIS.stop();
        try {
            // Wait for the 3 s cache (DEC-25) to expire before asserting.
            Thread.sleep(3500);
            cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().exists("Retry-After");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            REDIS.start();
        }
    }

    @Test
    void un_token_de_SERVICIO_no_pasa_por_el_chequeo_de_sesion() {
        // Service tokens are 100% stateless: no sid, no Redis.
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " +
                        TokenFactory.servicio("cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
```

- [ ] **Step 3: Correr y verify que fallan**

Run: `mvn -q test -Dtest=IssuerValidationIT+SessionInvalidationIT`
Expected: FAIL — falta `SecurityConfig`.

- [ ] **Step 4: Escribir `IssuerValidator` y `SessionValidator`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * DEC-07 + DEC-44 - `users-service` is a LOGICAL NAME, not a URL: that is why
 * `issuer-uri` is NOT used (Spring treats it as a URL and fires OIDC discovery
 * contra ella).
 *
 * What this validator adds over a bare JwtClaimValidator is the LOG: it names
 * the claim that failed, which turns "everything gives 401" into a grep.
 */
public class IssuerValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(IssuerValidator.class);

    private final String expected;

    public IssuerValidator(String expected) { this.expected = expected; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String iss = jwt.getClaimAsString(JwtClaimNames.ISS);

        if (iss == null) {
            log.warn("JWT_RECHAZADO reason=claim-ausente claim=iss");
            return fallo("El token no declara emisor.");
        }
        if (!expected.equals(iss)) {
            log.warn("JWT_RECHAZADO reason=claim-invalido claim=iss esperado={} recibido={}",
                    expected, iss);
            return fallo("Emisor no reconocido.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult fallo(String description) {
        // The `description` does NOT name the claim: that stays in the log.
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.security;

import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository;
import ar.edu.utn.frc.tup.p4.apigateway.repository.SessionRepository.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * v5 - single session. It is the gateway's ONLY Redis read.
 * It only applies to `type: user`: service tokens carry no `sid`.
 *
 * NO es un OAuth2TokenValidator, y NO PUEDE SERLO. Esa interfaz es SINCRONICA:
 * `OAuth2TokenValidatorResult validate(Jwt)`. Verificar la sesion exige leer
 * Redis, que es I/O, y el unico modo de meter I/O reactiva en una firma
 * sincronica es `.block()`.
 *
 * Reactor lo PROHIBE sobre el event loop de Netty, que es donde el
 * NimbusReactiveJwtDecoder corre sus validators. No es que ande lento: tira
 * IllegalStateException, que NO es una AuthenticationException, asi que no pasa
 * por el authenticationEntryPoint y sale como un 500 crudo. Todo token que
 * decodifica bien pero deberia rechazarse contesta 500 en vez de 401 o 503.
 *
 * Por eso esta clase solo DECIDE, devolviendo un Mono, y la decision la aplica
 * un WebFilter reactivo (`SessionGuard`, Step 5).
 *
 * DEC-01 - fail-closed distinguiendo la causa. La rama Unavailable se señaliza
 * distinto para que el filtro conteste 503 y no 401: decirle "tu sesion vencio"
 * a alguien cuya sesion esta perfecta, porque se cayo Redis, lo manda a
 * re-loguearse al pedo.
 */
@Component
public class SessionValidator {

    /** La decision, ya traducida. El filtro la mapea a una respuesta. */
    public enum Resultado { VIGENTE, SUPERADA, CERRADA, NO_VERIFICABLE }

    private static final Logger log = LoggerFactory.getLogger(SessionValidator.class);

    private final SessionRepository sessions;

    public SessionValidator(SessionRepository sessions) { this.sessions = sessions; }

    public Mono<Resultado> verificar(Jwt jwt) {
        if (!"user".equals(jwt.getClaimAsString("type"))) {
            return Mono.just(Resultado.VIGENTE);   // un token de servicio no lleva sid
        }

        String sidToken = jwt.getClaimAsString("sid");
        if (sidToken == null) {
            log.warn("JWT_RECHAZADO reason=claim-ausente claim=sid");
            return Mono.just(Resultado.CERRADA);
        }

        return sessions.findSid(jwt.getSubject()).map(status -> switch (status) {
            case SessionState.Active v when v.sid().equals(sidToken) -> Resultado.VIGENTE;
            case SessionState.Active v -> {
                log.warn("JWT_RECHAZADO reason=session-superseded sub={}", jwt.getSubject());
                yield Resultado.SUPERADA;
            }
            case SessionState.Absent ignored -> {
                log.warn("JWT_RECHAZADO reason=session-closed sub={}", jwt.getSubject());
                yield Resultado.CERRADA;
            }
            case SessionState.Unavailable nd -> {
                log.error("SESION_NO_VERIFICABLE sub={} - Redis no responde",
                        jwt.getSubject(), nd.cause());
                yield Resultado.NO_VERIFICABLE;
            }
        });
    }
}
```

- [ ] **Step 5: Escribir `SessionGuard`, que aplica la decision**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.security.SessionValidator;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Verifica la sesion unica DESPUES de que Security valido firma, exp e iss, y
 * ANTES de que el request se rutee.
 *
 * Es un WebFilter y no un OAuth2TokenValidator por el motivo que explica
 * `SessionValidator`: la interfaz del validator es sincronica y leer Redis no.
 */
@Component
public class SessionGuard implements WebFilter, Ordered {

    private final SessionValidator validador;

    public SessionGuard(SessionValidator validador) { this.validador = validador; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast)
                .flatMap(validador::verificar)
                // El defaultIfEmpty va ACA, sobre el Resultado, y NO como un
                // switchIfEmpty al final de la cadena. Los metodos de
                // ProblemDetails devuelven Mono<Void>, que SIEMPRE completa
                // vacio: un switchIfEmpty despues de ellos se dispara aunque ya
                // se haya escrito el 401, y el request rechazado sigue viaje al
                // destino. El cliente ve 401 y el backend recibe el request
                // igual. Es el mismo error que en PrivateRouteGuard.
                //
                // Sin Authentication -- ruta publica -- el guard no aplica.
                .defaultIfEmpty(SessionValidator.Resultado.VIGENTE)
                .flatMap(resultado -> switch (resultado) {
                    case VIGENTE -> chain.filter(exchange);
                    case SUPERADA -> ProblemDetails.write(exchange,
                            HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_SUPERSEDED,
                            "Session superseded",
                            "Another device signed in with this account.");
                    case CERRADA -> ProblemDetails.write(exchange,
                            HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_CLOSED,
                            "Session closed",
                            "The session is no longer active. Sign in again.");
                    // DEC-01: fail-closed, pero 503 y no 401. Reintentar SI
                    // sirve aca, a diferencia de una sesion cerrada.
                    case NO_VERIFICABLE -> ProblemDetails.withRetryAfter(exchange,
                            HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.SERVICE_UNAVAILABLE,
                            "Could not verify the session",
                            "Try again in a few seconds.", Duration.ofSeconds(5));
                });
    }

    /**
     * Despues de la cadena de autenticacion: necesita el Jwt ya validado en el
     * SecurityContext. La cadena de Security corre en -100
     * (SecurityWebFiltersOrder), asi que cualquier valor mayor sirve; 0 deja
     * margen por si hace falta intercalar algo antes.
     *
     * Ojo: este es el orden de los WebFilter, que es OTRO orden que el de los
     * GlobalFilter del pipeline de ruteo (@Order(1) a @Order(8)).
     */
    @Override
    public int getOrder() { return 0; }
}
```

> **`SecurityConfig` NO registra `SessionValidator` en el decoder.** El
> `NimbusReactiveJwtDecoder` lleva unicamente `JwtTimestampValidator` +
> `IssuerValidator`: los dos son sincronicos de verdad, no leen nada de red.
>
> **Avisá cuando esto entre a `main`.** A partir de ahi, un token de persona
> valido SIN sesion sembrada en Redis se rechaza con `session-closed`. Cualquier
> IT que mande `TokenFactory.persona(...)` sin sembrar empieza a fallar — el
> `DiscoveryAllowlistIT` de L7 es uno. La siembra va con
> `seedSession(redis, sub, "sid-1")` de `AbstractGatewayTest`.

- [ ] **Step 6: Escribir `SecurityConfig`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.config;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.JwtProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.security.*;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                                  JwtProperties props, SessionValidator sesion) {
        // DEC-44: no conditionals, no modes. Five lines.
        var validadores = List.<OAuth2TokenValidator<Jwt>>of(
                new JwtTimestampValidator(),
                new IssuerValidator(props.expectedIssuer()),
                sesion);

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validadores));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain chain(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)      // API stateless, sin cookies
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/api/*/public/**").permitAll()
                        .pathMatchers("/.well-known/**").permitAll()
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        // R3: NO hasRole/hasAuthority here. Everything private
                        // is authenticated() and nothing more. The role
                        // decision lives in the destination's @PreAuthorize.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(decoder))
                        .authenticationEntryPoint(entryPoint()))
                .build();
    }

    /** Maps the validator's reason to the right status and `type` (DEC-01). */
    private ServerAuthenticationEntryPoint entryPoint() {
        return (exchange, denegado) -> {
            String code = codigoDe(denegado);
            if (SessionValidator.ERROR_REDIS_CAIDO.equals(code)) {
                return ProblemDetails.withRetryAfter(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorTypes.SERVICE_UNAVAILABLE, "No se pudo verificar la sesion",
                        "Reintente en unos segundos.", Duration.ofSeconds(5));
            }
            URI type = switch (code) {
                case SessionValidator.ERROR_SESION_SUPERADA -> ErrorTypes.SESSION_SUPERSEDED;
                case SessionValidator.ERROR_SESION_CERRADA  -> ErrorTypes.SESSION_CLOSED;
                default -> ErrorTypes.NOT_AUTHENTICATED;
            };
            return ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED, type,
                    "No autenticado", "El token no es valido o la sesion no esta vigente.");
        };
    }

    private String codigoDe(Throwable t) {
        if (t instanceof OAuth2AuthenticationException e && e.getError() != null) {
            return e.getError().getErrorCode();
        }
        return "invalid_token";
    }
}
```

- [ ] **Step 7: Correr y verify que pasan**

Run: `mvn -q test -Dtest=IssuerValidationIT+SessionInvalidationIT`
Expected: PASS — 8 tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: autenticacion con validation de iss y sid (DEC-44, DEC-01)

El validador de iss loguea JWT_RECHAZADO nombrando el claim; el body del 401
no lo dice. El fail-mode distingue cause: Redis caido da 503 + Retry-After,
no 401 — decirle 'tu sesion vencio' a alguien cuya sesion esta bien lo manda
a re-loguearse al pedo.
R3: cero hasRole en la cadena. Todo lo privado es authenticated()."
```

---
### Task 7: Trazabilidad — `CorrelationIdFilter` y `LoggingFilter`

**Files:**
- Create: `src/main/java/…/filters/{CorrelationIdFilter,LoggingFilter}.java`
- Test: `src/test/java/…/filters/CorrelationIdFilterTest.java`
- Test: `src/test/java/…/filters/LoggingFilterTest.java`

**Interfaces:**
- Consumes: `IdentityHeaders` (T2).
- Produces: garantiza que a partir de `@Order(1)` **todo** exchange tiene `X-Request-Id` y `traceparent`.

- [ ] **Step 1: Escribir el test (falla)**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filtro = new CorrelationIdFilter();

    private ServerWebExchange run(MockServerHttpRequest req) {
        var ex = MockServerWebExchange.from(req);
        AtomicReference<ServerWebExchange> visto = new AtomicReference<>();
        GatewayFilterChain chain = e -> { visto.set(e); return Mono.empty(); };
        StepVerifier.create(filtro.filter(ex, chain)).verifyComplete();
        return visto.get();
    }

    @Test
    void genera_un_X_Request_Id_si_no_viene() {
        var mutado = run(MockServerHttpRequest.get("/api/users/me").build());
        assertThat(mutado.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID))
                .isNotBlank();
    }

    @Test
    void CONSERVA_el_traceparent_entrante() {
        // W3C Trace Context: if another service already started the trace, we
        // do not overwrite it - that splits the trail in two right at the edge.
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var mutado = run(MockServerHttpRequest.get("/api/users/me")
                .header("traceparent", traceparent).build());

        assertThat(mutado.getRequest().getHeaders().getFirst("traceparent")).isEqualTo(traceparent);
    }

    @Test
    void genera_un_traceparent_si_no_viene() {
        var mutado = run(MockServerHttpRequest.get("/api/users/me").build());
        assertThat(mutado.getRequest().getHeaders().getFirst("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    @Test
    void el_X_Request_Id_tambien_sale_en_la_RESPUESTA() {
        // Without this, a user reporting an error has no id to hand over.
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();
        assertThat(ex.getResponse().getHeaders().getFirst(IdentityHeaders.REQUEST_ID)).isNotBlank();
    }

    @Test
    void es_el_primer_filtro() {
        // The @Order values go in steps of 10 on purpose: it leaves room to
        // slot in PipelineOrderIT's spy filters (task 13) without touching
        // the relative order of the real ones.
        assertThat(filtro.getOrder()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: Escribir `CorrelationIdFilter`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Paso 2 del pipeline · @Order(1).
 *
 * An INCOMING `traceparent` is accepted (W3C Trace Context): if another service
 * started the trace, overwriting it splits the trail in two right at the edge.
 * That is why `traceparent` is NOT in `remove-request-headers` in
 * application.yml, unlike the five identity headers.
 *
 * The context propagates through the **Reactor context**, not MDC/ThreadLocal:
 * in WebFlux a request hops between threads and a ThreadLocal is lost.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CTX_REQUEST_ID = "requestId";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = valueOrGenerated(
                exchange.getRequest().getHeaders().getFirst(IdentityHeaders.REQUEST_ID),
                () -> UUID.randomUUID().toString());
        String traceparent = valueOrGenerated(
                exchange.getRequest().getHeaders().getFirst("traceparent"),
                CorrelationIdFilter::newTraceparent);

        ServerWebExchange mutado = exchange.mutate()
                .request(r -> r.header(IdentityHeaders.REQUEST_ID, requestId)
                               .header("traceparent", traceparent))
                .build();

        // Send it back in the response: it is the id a user can report.
        mutado.getResponse().getHeaders().set(IdentityHeaders.REQUEST_ID, requestId);

        return chain.filter(mutado)
                .contextWrite(Context.of(CTX_REQUEST_ID, requestId));
    }

    private static String newTraceparent() {
        byte[] trace = new byte[16];
        byte[] span = new byte[8];
        RANDOM.nextBytes(trace);
        RANDOM.nextBytes(span);
        HexFormat hex = HexFormat.of();
        return "00-" + hex.formatHex(trace) + "-" + hex.formatHex(span) + "-01";
    }

    private String valueOrGenerated(String entrante, java.util.function.Supplier<String> generador) {
        return (entrante == null || entrante.isBlank()) ? generador.get() : entrante;
    }

    @Override public int getOrder() { return 10; }
}
```

- [ ] **Step 3: Escribir el test y el `LoggingFilter`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingFilterTest {

    private ListAppender<ILoggingEvent> captured;
    private final LoggingFilter filtro = new LoggingFilter();

    @BeforeEach
    void capturarLogs() {
        captured = new ListAppender<>();
        captured.start();
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).addAppender(captured);
    }

    @AfterEach
    void soltar() {
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).detachAppender(captured);
    }

    @Test
    void NUNCA_loguea_el_header_Authorization() {
        // A token in the log is a stolen token, for whoever reads logs.
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me")
                .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.SECRETO.firma").build());

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(captured.list).allSatisfy(evento ->
                assertThat(evento.getFormattedMessage())
                        .doesNotContain("Bearer").doesNotContain("SECRETO").doesNotContain("eyJ"));
    }

    @Test
    void NUNCA_loguea_el_body() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/users/public/auth/token")
                .body("{\"clientSecret\":\"un-secreto\"}"));

        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(captured.list).allSatisfy(evento ->
                assertThat(evento.getFormattedMessage()).doesNotContain("un-secreto"));
    }

    @Test
    void loguea_metodo_path_y_status() {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        StepVerifier.create(filtro.filter(ex, e -> Mono.empty())).verifyComplete();

        assertThat(captured.list).anySatisfy(evento ->
                assertThat(evento.getFormattedMessage()).contains("GET").contains("/api/users/me"));
    }

    @Test
    void es_el_segundo_filtro() { assertThat(filtro.getOrder()).isEqualTo(20); }
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Paso 3 del pipeline · @Order(2).
 *
 * What it NEVER logs: bodies, tokens, the Authorization header, or the
 * clientSecret of /auth/token. A token in the log is a stolen token, for
 * whoever gets around to reading logs.
 *
 * The trace id is not logged by hand: it comes from logback-spring.xml's pattern.
 */
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long inicio = System.nanoTime();
        var req = exchange.getRequest();

        return chain.filter(exchange).doFinally(señal -> {
            var status = exchange.getResponse().getStatusCode();
            log.info("{} {} -> {} ({} ms)",
                    req.getMethod(), req.getPath().value(),
                    status == null ? "-" : status.value(),
                    (System.nanoTime() - inicio) / 1_000_000);
        });
    }

    @Override public int getOrder() { return 20; }
}
```

- [ ] **Step 4: Correr y verify que pasan**

Run: `mvn -q test -Dtest=CorrelationIdFilterTest+LoggingFilterTest`
Expected: PASS — 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: correlacion y logging (pasos 2 y 3 del pipeline)

El traceparent entrante se conserva (W3C): pisarlo parte el rastreo en dos
justo en el borde. La correlacion viaja por el contexto de Reactor, no por
MDC: en WebFlux un request salta de hilo y un ThreadLocal se pierde.
Hay test de que Authorization y el body nunca aparecen en el log."
```

---

### Task 8: Los dos guards de ruta

> **Sembrá la sesión con el helper de la base, no a mano.**
> `AbstractGatewayTest` expone `seedSession(redis, userId, sid)`,
> `clearSession(redis, userId)` y `sessionKey(userId)`. El formato de la key lo
> escribe el login de `users-service` (`DEC-22`), no el Gateway: si cada IT lo
> arma por su cuenta, alcanza con que alguien ponga `sessions:` en plural para
> que su test pase en verde probando nada. Y el `sid` sembrado tiene que ser
> **el mismo** que el claim `sid` del token, o `SessionGuard` corta con
> `session-superseded` — que es justo lo que estos tests no están probando.
>
> Los snippets de abajo la siembran a mano por legibilidad; en tu código usá el
> helper.


**Files:**
- Create: `src/main/java/…/filters/{PublicRouteGuard,PrivateRouteGuard}.java`
- Test: `src/test/java/…/filters/PrivateRouteGuardTest.java`
- Test: `src/test/java/…/integration/PublicPrivateRouteIT.java`

**Interfaces:**
- Consumes: `GatewayRoutingProperties` (T2), `PrincipalContext` (T2), `ProblemDetails` (T4).
- Produces: garantía de que a partir de `@Order(5)` todo request privado tiene un `Jwt` bien formado en el `SecurityContext`.

- [ ] **Step 1: Escribir el test de integración (falla)** — criterio de DoD #5

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class PublicPrivateRouteIT extends AbstractGatewayTest {

    @Test
    void una_ruta_public_pasa_SIN_token() {
        cliente.post().uri("/api/users/public/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void el_mismo_micro_fuera_de_public_SIN_token_da_401() {
        cliente.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void el_JWKS_pasa_sin_token_y_LLEGA_AL_DESTINO() {
        // DEC-27 - the bug this route had: it passed Security and the three
        // guards and then died on a 404 from the gateway itself, because the
        // genera Path=/api/{nombre}/**. Necesita ruta estatica.
        cliente.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk();
    }

    @Test
    void una_ruta_privada_con_token_valido_pasa() {
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(UUID.randomUUID(), "sid-1"))
                .exchange().expectStatus().isOk();
    }
}
```

En `AbstractGatewayTest`, write en Redis el `sid` correspondiente antes de los tests que usan token de persona, o usar un `@BeforeEach` que siembre `session:{sub}`.

- [ ] **Step 2: Escribir el test unitario del guard privado (falla)**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defence in depth: if a configuration bug let something private through
 * unauthenticated, this filter cuts it BEFORE IdentityPropagationFilter injects
 * empty headers the destination would trust blindly.
 */
class PrivateRouteGuardTest {

    private final PrivateRouteGuard guard = new PrivateRouteGuard();

    private Jwt.Builder jwt() {
        return Jwt.withTokenValue("x").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("s");
    }

    private HttpStatus run(Jwt token) {
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());
        Mono<Void> resultado = guard.filter(ex, e -> Mono.empty());
        if (token != null) {
            resultado = resultado.contextWrite(ReactiveSecurityContextHolder
                    .withAuthentication(new JwtAuthenticationToken(token,
                            List.of(new SimpleGrantedAuthority("ROLE_STUDENT")))));
        // 🔴 El constructor de DOS argumentos, no el de uno: el de un
        // argumento no llama a setAuthenticated(true), asi que el guard
        // rechaza por isAuthenticated y el caso verde nunca se ejercita.
        // Verificado: un argumento -> isAuthenticated() == false.
        }
        StepVerifier.create(resultado).verifyComplete();
        return (HttpStatus) ex.getResponse().getStatusCode();
    }

    @Test
    void sin_Authentication_en_una_ruta_privada_corta_con_401() {
        assertThat(run(null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void un_type_desconocido_corta_con_401() {
        assertThat(run(jwt().claim("type", "robot").claim("roles", List.of("X")).build()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void type_user_con_roles_VACIOS_corta_con_401() {
        // If it happened, X-User-Roles would go out empty and the destination
        // would have a principal with no role: neither allowed nor denied.
        assertThat(run(jwt().claim("type", "user").claim("roles", List.of()).build()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void type_service_SIN_el_rol_MS_corta_con_401() {
        assertThat(run(jwt().claim("type", "service").claim("roles", List.of("STUDENT")).build()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void un_token_bien_formado_pasa() {
        assertThat(run(jwt().claim("type", "user").claim("roles", List.of("STUDENT"))
                .claim("sid", "s").claim("est", "ACTIVE").claim("pwd", false)
                .claim("onb", false).build())).isNull();   // no escribió status: siguió la cadena
    }

    @Test
    void un_rol_de_ADMIN_no_recibe_trato_distinto_que_uno_de_ALUMNO() {
        // R3 - DoD criterion #11: it checks the SHAPE of the token, never the
        // role against the route. Both pass; the permission decision belongs to
        // the destination's @PreAuthorize, not here.
        Jwt admin = jwt().claim("type", "user").claim("roles", List.of("ADMIN"))
                .claim("sid", "s").claim("est", "ACTIVE").claim("pwd", false)
                .claim("onb", false).build();
        Jwt alumno = jwt().claim("type", "user").claim("roles", List.of("STUDENT"))
                .claim("sid", "s").claim("est", "ACTIVE").claim("pwd", false)
                .claim("onb", false).build();

        assertThat(run(admin)).isEqualTo(run(alumno)).isNull();
    }
}
```

- [ ] **Step 3: Escribir los dos guards**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.GatewayRoutingProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Paso 4 del pipeline · @Order(3).
 *
 * Marks the exchange as public according to the route convention of §5:
 * `/api/{name}/public/**`. The guards below consume it: they need to know
 * whether a token is required WITHOUT each one re-parsing the path.
 */
@Component
public class PublicRouteGuard implements GlobalFilter, Ordered {

    public static final String ATTR_ES_PUBLICA = "gateway.rutaPublica";

    private final GatewayRoutingProperties props;

    public PublicRouteGuard(GatewayRoutingProperties props) { this.props = props; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean publica = path.startsWith("/.well-known/")
                || path.startsWith("/fallback/")
                || isPublicServiceRoute(path);
        exchange.getAttributes().put(ATTR_ES_PUBLICA, publica);
        return chain.filter(exchange);
    }

    /** /api/{name}/public/... - the `public` segment is the THIRD one, always. */
    private boolean isPublicServiceRoute(String path) {
        String[] parts = path.split("/");
        // ["", "api", "users", "public", ...]
        return parts.length >= 4
                && props.pathPrefix().equals("/" + parts[1])
                && "public".equals(parts[3]);
    }

    @Override public int getOrder() { return 30; }
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Paso 5 del pipeline · @Order(4).
 *
 * Defence in depth. It checks the SHAPE of the token, not the permission:
 * this is NOT authorizing by role (R3). There is no comparison against ADMIN,
 * PROFESSOR or STUDENT in this class, and there must never be one.
 */
@Component
public class PrivateRouteGuard implements GlobalFilter, Ordered {

    public static final String ATTR_JWT = "gateway.jwt";
    private static final Logger log = LoggerFactory.getLogger(PrivateRouteGuard.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (Boolean.TRUE.equals(exchange.getAttribute(PublicRouteGuard.ATTR_ES_PUBLICA))) {
            return chain.filter(exchange);
        }

        // 🔴 Ausente y presente se resuelven ANTES de correr la cadena, con un
        // Decision y un defaultIfEmpty. La forma que parece natural NO sirve:
        //
        //     .flatMap(jwt -> { ...; return chain.filter(exchange); })
        //     .switchIfEmpty(Mono.defer(() -> reject(exchange)))
        //
        // porque `chain.filter(exchange)` es un `Mono<Void>` y un Mono<Void>
        // completa VACIO cuando todo salio bien. Asi que switchIfEmpty no
        // distingue "no habia autenticacion" de "la cadena siguio y termino
        // bien": dispara en los dos casos, y el Gateway contesta 401 a TODO
        // request privado valido.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast)
                .map(jwt -> new Decision(jwt, coherence(jwt)))
                .defaultIfEmpty(new Decision(null, "sin-authentication"))
                .flatMap(decision -> {
                    if (decision.reason() != null) {
                        log.warn("JWT_RECHAZADO reason={} sub={}", decision.reason(),
                                decision.jwt() == null ? "-" : decision.jwt().getSubject());
                        return reject(exchange);
                    }
                    exchange.getAttributes().put(ATTR_JWT, decision.jwt());
                    return chain.filter(exchange);
                });
    }

    /** El Jwt y el reason del rechazo, o null si esta bien formado. */
    private record Decision(Jwt jwt, String reason) { }

    /** Returns the reason, or null when the token is well formed. */
    private String coherence(Jwt jwt) {
        String typeClaim = jwt.getClaimAsString("type");
        if (typeClaim == null) return "claim-ausente-type";

        PrincipalType type;
        try { type = PrincipalType.from(typeClaim); } catch (IllegalArgumentException e) { return "type-desconocido"; }

        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) return "claim-ausente-sub";

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) return "claim-ausente-roles";

        // A service token WITHOUT the MS role is not a service token.
        if (type == PrincipalType.SERVICE && !roles.contains("MS")) return "servicio-sin-MS";

        return null;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        return ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                ErrorTypes.NOT_AUTHENTICATED, "No autenticado",
                "El token no es valido para esta ruta.");
    }

    @Override public int getOrder() { return 40; }
}
```

- [ ] **Step 4: Correr y verify que pasan**

Run: `mvn -q test -Dtest=PrivateRouteGuardTest+PublicPrivateRouteIT`
Expected: PASS — 10 tests.

- [ ] **Step 5: Verificar R3 a mano**

Run: `grep -rn "hasRole\|hasAuthority\|\"ADMIN\"\|\"PROFESSOR\"\|\"STUDENT\"" src/main/java/`
Expected: **cero resultados**. Criterio de DoD #11. La única mención permitida a `MS` es la de `coherencia()`, que verifica forma, no permiso.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: guards de ruta publica y privada (pasos 4 y 5)

PrivateRouteGuard verifica la FORMA del token, nunca el permiso (R3).
Existe para que un bug de configuracion no deje pasar algo privado sin
autenticar y termine inyectando headers vacios que el destino confia."
```

---

### Task 9: `AccountStateGuard` — el gate de cuenta sobre rutas ajenas

**Files:**
- Create: `src/main/java/…/filters/AccountStateGuard.java`
- Test: `src/test/java/…/integration/AccountStateGuardIT.java`

**Interfaces:**
- Consumes: `PrivateRouteGuard.ATTR_JWT` (T8), `ProblemDetails` (T4), `ErrorTypes` (T2).
- Produces: nada para tareas siguientes.

- [ ] **Step 1: Escribir el test (falla)** — criterios de DoD #7d y #7e

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

/**
 * DEC-23 - this is the flow of `manifiesto-flujos` §11, which is NOT
 * implementable as drawn: the diagram shows the 403 coming out of the
 * `users-svc·users/` lane for an `/api/cursos/**` route - a service that never
 * sees that traffic and cannot block it.
 */
class AccountStateGuardIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String tokenWithStatus(UUID u, String est, boolean pwd, boolean onb) {
        redis.opsForValue().set("session:" + u, "sid-1").block();
        return TokenFactory.persona(u, "sid-1",
                b -> b.claim("est", est).claim("pwd", pwd).claim("onb", onb));
    }

    @Test
    void onboarding_pendiente_recibe_403_en_una_ruta_de_OTRO_micro() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", false, true))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/onboarding-pending"));
    }

    @Test
    void la_MISMA_cuenta_pasa_en_una_ruta_de_users_service() {
        // The whole rule: if the account is not enabled, ONLY /api/users/** and
        // /api/*/public/** are allowed. The FINE gate (per route, with its
        // exemptions) is users-service's; the gateway applies the coarse one.
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", false, true))
                .exchange().expectStatus().isOk();
    }

    @Test
    void una_cuenta_PENDIENTE_CURSO_recibe_403_con_el_estado_en_el_cuerpo() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "PENDING_COURSE", false, false))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.accountStatus").isEqualTo("PENDING_COURSE");
    }

    @Test
    void debe_cambiar_password_recibe_403_con_su_propio_type() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", true, false))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/password-change-required"));
    }

    @Test
    void una_cuenta_habilitada_pasa_a_cualquier_micro() {
        UUID u = UUID.randomUUID();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenWithStatus(u, "ACTIVE", false, false))
                .exchange().expectStatus().isOk();
    }

    @Test
    void un_token_de_persona_SIN_los_claims_es_rechazado_y_el_log_los_nombra() {
        // DEC-44 - DoD criterion #7d. This is the "old users-service against a
        // new gateway" case, which now fails legibly.
        UUID u = UUID.randomUUID();
        redis.opsForValue().set("session:" + u, "sid-1").block();
        String sinClaims = TokenFactory.persona(u, "sid-1",
                b -> b.claim("est", null).claim("pwd", null).claim("onb", null));

        cliente.get().uri("/api/cursos/mis-cursos")
                .header("Authorization", "Bearer " + sinClaims)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void un_token_de_SERVICIO_no_atraviesa_este_filtro() {
        // An MS does not stand for a person with an account: no status to check.
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " +
                        TokenFactory.servicio("cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
```

- [ ] **Step 2: Correr y verify que falla**

Run: `mvn -q test -Dtest=AccountStateGuardIT`
Expected: FAIL — falta `AccountStateGuard`.

- [ ] **Step 3: Escribir el filtro**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * Pipeline step 6 - @Order(5) - DEC-23 - NOT in the manifest.
 *
 * The COARSE account-status gate. The whole rule, in one line:
 *
 *   if the principal is a person and the account is not enabled, ONLY
 *   permiten /api/users/** y /api/{x}/public/**.
 *
 * It composes cleanly because ALL the routes exempt from the three fine gates
 * belong to users-service: the gateway needs to know nobody's routes.
 *
 * This does NOT violate R3: R3 says the gateway does not authorize by ROLE, and
 * it does not - it never reads `roles` nor compares role against route. An
 * account's status is not a role: it is "who you are" vs "whether you can act".
 */
@Component
public class AccountStateGuard implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccountStateGuard.class);
    private static final String PREFIJO_USERS = "/api/users/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (Boolean.TRUE.equals(exchange.getAttribute(PublicRouteGuard.ATTR_ES_PUBLICA))) {
            return chain.filter(exchange);
        }
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);
        if (jwt == null || !"user".equals(jwt.getClaimAsString("type"))) {
            return chain.filter(exchange);   // servicio: sin status que mirar
        }

        String est = jwt.getClaimAsString("est");
        Boolean pwd = jwt.getClaim("pwd");
        Boolean onb = jwt.getClaim("onb");

        // DEC-44: all three are MANDATORY in a person token. Missing means a
        // users-service that does not emit them yet: it is rejected, and the
        // log names which one is missing.
        if (est == null || pwd == null || onb == null) {
            log.warn("JWT_RECHAZADO reason=claim-ausente claim={} sub={}",
                    est == null ? "est" : pwd == null ? "pwd" : "onb", jwt.getSubject());
            return ProblemDetails.write(exchange, HttpStatus.UNAUTHORIZED,
                    ErrorTypes.NOT_AUTHENTICATED, "No autenticado",
                    "El token no es valido para esta ruta.");
        }

        boolean habilitada = "ACTIVE".equals(est) && !pwd && !onb;
        if (habilitada) return chain.filter(exchange);

        // Not enabled: it can only talk to users-service.
        if (exchange.getRequest().getPath().value().startsWith(PREFIJO_USERS)) {
            return chain.filter(exchange);
        }

        // The SAME types users-service returns from its fine gates, so the
        // frontend has a single handling branch.
        if (!"ACTIVE".equals(est)) {
            return reject(exchange, ErrorTypes.PENDING_ACCOUNT, "Cuenta pending de validation",
                    "La cuenta no esta activa.", Map.of("accountStatus", est));
        }
        if (pwd) {
            return reject(exchange, ErrorTypes.PASSWORD_CHANGE_REQUIRED,
                    "Cambio de contrasena requerido",
                    "Debe cambiar su contrasena antes de continuar.", Map.of());
        }
        return reject(exchange, ErrorTypes.ONBOARDING_PENDING, "Onboarding pending",
                "Complete el onboarding antes de continuar.", Map.of());
    }

    private Mono<Void> reject(ServerWebExchange exchange, URI type, String titulo,
                              String detalle, Map<String, Object> extras) {
        exchange.getAttributes().put(ProblemDetails.ATTR_EXTRAS, extras);
        return ProblemDetails.write(exchange, HttpStatus.FORBIDDEN, type, titulo, detalle);
    }

    @Override public int getOrder() { return 50; }
}
```

- [ ] **Step 4: Correr y verify que pasa**

Run: `mvn -q test -Dtest=AccountStateGuardIT`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: AccountStateGuard, el gate de cuenta sobre rutas ajenas (DEC-23)

flujos §11 dibuja el 403 saliendo de users-service para una ruta /api/cursos:
imposible, ese servicio no ve el trafico. El Gateway pone el gate grueso con
una regla que no necesita conocer las rutas de nadie.
No viola R3: el status de una cuenta no es un role."
```

---
### Task 10: `ServiceAudienceFilter` — validación de `aud` post-routing

**Files:**
- Create: `src/main/java/…/filters/ServiceAudienceFilter.java`
- Test: `src/test/java/…/integration/ServiceAudienceIT.java`

**Interfaces:**
- Consumes: `PrivateRouteGuard.ATTR_JWT` (T8), `ProblemDetails` (T4).
- Produces: nada para tareas siguientes.

- [ ] **Step 1: Escribir el test (falla)** — criterio de DoD #6

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import org.junit.jupiter.api.Test;

/**
 * DEC-04 - damage containment: a token issued to talk to one service does NOT
 * work against another. If a client secret leaks, the blast radius is limited
 * to the destination that token was requested for, instead of being a key to
 * the whole platform.
 */
class ServiceAudienceIT extends AbstractGatewayTest {

    @Test
    void un_token_con_aud_CORRECTO_pasa() {
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void un_token_con_aud_de_OTRO_destino_da_403() {
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "cursos-service", "users.profile.read"))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.type").value(t ->
                        org.assertj.core.api.Assertions.assertThat((String) t)
                                .endsWith("/invalid-audience"));
    }

    @Test
    void un_token_de_servicio_SIN_aud_da_403() {
        // Without this a token with no aud would pass everywhere, which is what
        // this filter exists to prevent. There is no permissive default.
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", null, "users.profile.read"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void un_token_de_PERSONA_no_es_afectado_por_este_filtro() {
        // The annex §7.4 case: Cursos forwards the professor's token to
        // GET /profile/{id}. That token carries NO aud, and it should not:
        // the filter only looks at type: service (DEC-36).
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " +
                        TokenFactory.persona(java.util.UUID.randomUUID(), "sid-1"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void el_aud_se_compara_contra_el_serviceId_RESUELTO_no_contra_el_path() {
        // Comparing against the path would be fragile: the path is /api/users/...
        // and the serviceId is users-service. The right source is the resolved route.
        cliente.get().uri("/api/users/algo/anidado/profundo")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();
    }
}
```

- [ ] **Step 2: Correr y verify que falla**

Run: `mvn -q test -Dtest=ServiceAudienceIT`
Expected: FAIL — falta `ServiceAudienceFilter`.

- [ ] **Step 3: Escribir el filtro**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Pipeline step 7 - @Order(6) - DEC-04 - NOT in the manifest.
 *
 * Why it does not live in Spring Security: the Security chain runs BEFORE the
 * route is resolved, so the destination is not known yet. This filter is
 * post-routing, and that is why it can compare `aud` against the serviceId
 * ya resuelto.
 */
@Component
public class ServiceAudienceFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ServiceAudienceFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);
        // Only type: service. A person token carries no aud, and must not (DEC-36).
        if (jwt == null || !"service".equals(jwt.getClaimAsString("type"))) {
            return chain.filter(exchange);
        }

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            // Should not happen: RoutePredicateHandlerMapping sets it BEFORE the
            // GlobalFilter chain starts. If it does happen, fail closed.
            log.error("GATEWAY_ROUTE_ATTR ausente en @Order(6) — ver PipelineOrderIT");
            return reject(exchange, "No se pudo determinar el destino.");
        }

        String destino = route.getUri().getHost() == null
                ? "" : route.getUri().getHost().toLowerCase(Locale.ROOT);
        List<String> aud = jwt.getAudience();

        if (aud == null || aud.isEmpty()) {
            log.warn("AUD_RECHAZADO reason=aud-ausente cliente={} destino={}",
                    jwt.getSubject(), destino);
            return reject(exchange, "El token de servicio no declara destino.");
        }
        if (!aud.contains(destino)) {
            log.warn("AUD_RECHAZADO reason=aud-no-coincide cliente={} aud={} destino={}",
                    jwt.getSubject(), aud, destino);
            return reject(exchange, "El token no fue emitido para este destino.");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, String detalle) {
        return ProblemDetails.write(exchange, HttpStatus.FORBIDDEN,
                ErrorTypes.INVALID_AUDIENCE, "Audiencia invalida", detalle);
    }

    @Override public int getOrder() { return 60; }
}
```

- [ ] **Step 4: Correr y verify que pasa**

Run: `mvn -q test -Dtest=ServiceAudienceIT`
Expected: PASS — 5 tests.

> Si el test de `aud` correcto fallara con `GATEWAY_ROUTE_ATTR ausente`, **NO subir el order por encima de 10000**: eso pondría este filtro después de `IdentityPropagationFilter@7` y el destino recibiría headers de identidad de un token cuyo `aud` no se validó — se rompe el anti-spoofing, que es justo lo que el filtro sostiene. La salida correcta sería mover **los dos** por encima de 10000, conservando su orden relativo.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: ServiceAudienceFilter post-routing (DEC-04)

No puede vivir en Spring Security: esa cadena corre antes de resolver la ruta,
asi que ahi todavia no se sabe cual es el destino.
Contencion de dano: un secreto filtrado no es una llave para toda la plataforma."
```

---

### Task 11: `IdentityPropagationFilter` — el anti-spoofing

**Files:**
- Create: `src/main/java/…/filters/IdentityPropagationFilter.java`
- Test: `src/test/java/…/integration/IdentityPropagationIT.java`

**Interfaces:**
- Consumes: `PrincipalContext` (T2), `IdentityHeaders` (T2), `PrivateRouteGuard.ATTR_JWT` (T8).
- Produces: el contrato que consumen los otros once microservicios.

- [ ] **Step 1: Escribir el test (falla)** — criterio de DoD #10

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropagationIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String tokenDe(UUID u) {
        redis.opsForValue().set("session:" + u, "sid-1").block();
        return TokenFactory.persona(u, "sid-1");
    }

    @Test
    void un_header_de_identidad_FALSIFICADO_es_reemplazado_por_el_del_token()
            throws InterruptedException {
        // DoD criterion #10. THE gateway security test: if this one
        // falla, cualquiera es ADMIN mandando un header.
        UUID real = UUID.randomUUID();
        UUID atacante = UUID.randomUUID();

        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenDe(real))
                .header(IdentityHeaders.USER_ID, atacante.toString())
                .header(IdentityHeaders.USER_ROLES, "ADMIN")
                .header(IdentityHeaders.PRINCIPAL_TYPE, "service")
                .exchange().expectStatus().isOk();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getHeader(IdentityHeaders.USER_ID)).isEqualTo(real.toString());
        assertThat(recibido.getHeader(IdentityHeaders.USER_ROLES)).isEqualTo("STUDENT");
        assertThat(recibido.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("user");
    }

    @Test
    void en_una_ruta_PUBLICA_los_headers_entrantes_se_BORRAN_y_no_se_inyecta_ninguno()
            throws InterruptedException {
        // Seeing no X-Principal-Type, the destination knows it is public traffic.
        // If the stripping did not apply on public routes it would be the
        // biggest hole of all: a tokenless route where you declare yourself ADMIN.
        cliente.post().uri("/api/users/public/auth/login")
                .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                .header(IdentityHeaders.USER_ROLES, "ADMIN")
                .exchange().expectStatus().isOk();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getHeader(IdentityHeaders.USER_ID)).isNull();
        assertThat(recibido.getHeader(IdentityHeaders.USER_ROLES)).isNull();
        assertThat(recibido.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isNull();
    }

    @Test
    void un_token_de_servicio_inyecta_X_Service_Id_y_X_Service_Scopes()
            throws InterruptedException {
        cliente.get().uri("/api/users/profile/x")
                .header("Authorization", "Bearer " + TokenFactory.servicio(
                        "cursos-service", "users-service", "users.profile.read"))
                .exchange().expectStatus().isOk();

        RecordedRequest recibido = ultimoRequestAlDestino();
        assertThat(recibido.getHeader(IdentityHeaders.PRINCIPAL_TYPE)).isEqualTo("service");
        assertThat(recibido.getHeader(IdentityHeaders.SERVICE_ID)).isEqualTo("cursos-service");
        // DEC-05: MS first, comma with no space.
        assertThat(recibido.getHeader(IdentityHeaders.SERVICE_SCOPES))
                .isEqualTo("MS,users.profile.read");
        // A service token carries no person headers.
        assertThat(recibido.getHeader(IdentityHeaders.USER_ID)).isNull();
    }

    @Test
    void el_Authorization_original_se_REENVIA_al_destino() throws InterruptedException {
        // DEC-03: it enables internal zero-trust. It does not relax anti-spoofing:
        // the token is signed, the X-* headers are not.
        String token = tokenDe(UUID.randomUUID());
        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + token)
                .exchange().expectStatus().isOk();

        assertThat(ultimoRequestAlDestino().getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void el_traceparent_llega_al_destino() throws InterruptedException {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + tokenDe(UUID.randomUUID()))
                .header("traceparent", traceparent)
                .exchange().expectStatus().isOk();

        assertThat(ultimoRequestAlDestino().getHeader("traceparent")).isEqualTo(traceparent);
    }
}
```

- [ ] **Step 2: Correr y verify que falla**

Run: `mvn -q test -Dtest=IdentityPropagationIT`
Expected: FAIL — falta el filtro.

- [ ] **Step 3: Escribir el filtro**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.constants.PrincipalType;
import ar.edu.utn.frc.tup.p4.apigateway.security.PrincipalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Paso 8 del pipeline · @Order(7).
 *
 * THE ORDER OF THE TWO OPERATIONS IS PART OF THE CONTRACT:
 *   1. FIRST strip the five reserved headers, wherever they came from.
 *      ALWAYS - public routes included, where there is no token to inject.
 *   2. THEN inject the set derived ONLY from the validated Jwt.
 *
 * A value is never copied from the incoming request into an identity header.
 * The only source is the Jwt. Inverting this, or skipping the strip on
 * public routes, anyone is ADMIN by sending a header.
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdentityPropagationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Jwt jwt = exchange.getAttribute(PrivateRouteGuard.ATTR_JWT);

        ServerWebExchange mutado = exchange.mutate().request(r -> {
            // PASO 1 · borrar SIEMPRE. Anti-spoofing.
            r.headers(h -> IdentityHeaders.RESERVED.forEach(h::remove));

            // STEP 2 - inject only when there is a validated identity.
            if (jwt == null) return;

            PrincipalContext p = PrincipalContext.from(jwt);
            r.header(IdentityHeaders.PRINCIPAL_TYPE, p.type().claim());

            if (p.type() == PrincipalType.USER) {
                r.header(IdentityHeaders.USER_ID, p.subject());
                r.header(IdentityHeaders.USER_ROLES, p.rolesHeader());
            } else {
                r.header(IdentityHeaders.SERVICE_ID, p.subject());
                r.header(IdentityHeaders.SERVICE_SCOPES, p.scopesHeader());
                // DEC-10 - on_behalf_of stays HERE, in the gateway's log.
                // No X-On-Behalf-Of header is created: users-service does not
                // parse the JWT (DEC-08), so this log is the ONLY record of it.
                if (p.onBehalfOf() != null) {
                    log.info("ON_BEHALF_OF servicio={} actor={} ruta={}",
                            p.subject(), p.onBehalfOf(), exchange.getRequest().getPath().value());
                }
            }
            // The original Authorization is NOT touched: forwarded as is (DEC-03).
        }).build();

        return chain.filter(mutado);
    }

    @Override public int getOrder() { return 70; }
}
```

- [ ] **Step 4: Correr y verify que pasa**

Run: `mvn -q test -Dtest=IdentityPropagationIT`
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: propagacion de identidad con borrado previo (anti-spoofing)

Borra SIEMPRE los cinco headers reservados, incluso en rutas publicas: si el
borrado no aplicara ahi, seria el agujero mas grande de todos — una ruta sin
token donde uno se declara ADMIN.
DEC-10: on_behalf_of queda en el log del Gateway, que es su unico registro."
```

---

### Task 12: `RateLimitFilter` y la IP real

**Files:**
- Create: `src/main/java/…/ratelimit/{RateLimitKeyResolver,TokenBucket}.java` + `impl/`
- Create: `src/main/java/…/filters/RateLimitFilter.java`
- Test: `src/test/java/…/integration/RateLimitForwardedIT.java`

**Interfaces:**
- Consumes: `RateLimitProperties` (T2), `ProblemDetails` (T4), `PrincipalContext` (T2).
- Produces: nada para tareas siguientes.

- [ ] **Step 1: Escribir el test (falla)** — criterio de DoD #7f

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-24 - the gateway limits by IP (a flood guard); auth/ limits by e-mail (a
 * brute-force guard). Different keys: they cannot fire on the same condition,
 * and whichever trips first answers.
 *
 * The gateway is the ONLY one that can cut before the round trip to the
 * database and the BCrypt, which is expensive on purpose (~100 ms).
 */
@TestPropertySource(properties = {
        "gateway.rate-limit.expensive-routes[0].path=/api/users/public/auth/**",
        "gateway.rate-limit.expensive-routes[0].key=IP",
        "gateway.rate-limit.expensive-routes[0].capacity=3",
        "gateway.rate-limit.expensive-routes[0].refill-per-minute=3"
})
class RateLimitForwardedIT extends AbstractGatewayTest {

    @Test
    void dos_IPs_distintas_detras_del_MISMO_proxy_consumen_buckets_SEPARADOS() {
        // The gotcha that never shows up in development: a naive implementation
        // takes the load balancer's IP and rate-limits THE WHOLE INTERNET as if
        // it were one client. The limiter is useless and nobody finds out until
        // the exam-week peak.
        for (int i = 0; i < 3; i++) {
            cliente.post().uri("/api/users/public/auth/login")
                    .header("X-Forwarded-For", "203.0.113.10")
                    .exchange().expectStatus().isOk();
        }
        cliente.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.10")
                .exchange().expectStatus().isEqualTo(429);

        // The OTHER IP still has its full budget.
        cliente.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.99")
                .exchange().expectStatus().isOk();
    }

    @Test
    void el_429_lleva_Retry_After_y_el_type_compartido() {
        for (int i = 0; i < 4; i++) {
            cliente.post().uri("/api/users/public/auth/login")
                    .header("X-Forwarded-For", "203.0.113.20").exchange();
        }
        cliente.post().uri("/api/users/public/auth/login")
                .header("X-Forwarded-For", "203.0.113.20")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.type").value(t ->
                        assertThat((String) t).endsWith("/too-many-attempts"));
    }

    @Test
    void una_ruta_que_NO_esta_en_expensive_routes_no_se_limita() {
        // The filter is a no-op off the list: we do not want to limit everything.
        for (int i = 0; i < 20; i++) {
            cliente.get().uri("/api/users/public/legal/terms")
                    .header("X-Forwarded-For", "203.0.113.30")
                    .exchange().expectStatus().isOk();
        }
    }
}
```

- [ ] **Step 2: Escribir las interfaces y las implementaciones**

```java
package ar.edu.utn.frc.tup.p4.apigateway.ratelimit;

import org.springframework.web.server.ServerWebExchange;

public interface RateLimitKeyResolver {
    String resolve(ServerWebExchange exchange, String tipoClave);
}
```
```java
package ar.edu.utn.frc.tup.p4.apigateway.ratelimit;

public interface TokenBucket {
    /** true when there is budget left; false when it ran out. */
    boolean consume(String key, int capacidad, int recargaPorMinuto);
    java.time.Duration suggestedWait(String key);
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.RateLimitKeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * DEC-24 - the real IP comes from X-Forwarded-For, and only if the previous hop
 * is a trusted proxy. Taking plain `getRemoteAddress()` behind a load balancer
 * ALWAYS gives the balancer's IP: the limiter would treat the whole internet as
 * a single client. And trusting the header without checking the proxy lets
 * anyone invent an IP per request and evade the limit.
 */
@Component
public class PrincipalRateLimitKeyResolver implements RateLimitKeyResolver {

    private final RateLimitProperties props;

    public PrincipalRateLimitKeyResolver(RateLimitProperties props) { this.props = props; }

    @Override
    public String resolve(ServerWebExchange exchange, String tipoClave) {
        return switch (tipoClave) {
            case "USER"    -> "user:"    + header(exchange, IdentityHeaders.USER_ID);
            case "SERVICE" -> "service:" + header(exchange, IdentityHeaders.SERVICE_ID);
            default        -> "ip:"      + ipReal(exchange);
        };
    }

    private String ipReal(ServerWebExchange exchange) {
        InetSocketAddress remoto = exchange.getRequest().getRemoteAddress();
        String directa = remoto == null ? "desconocida" : remoto.getAddress().getHostAddress();

        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff == null || xff.isBlank() || !isTrustedProxy(directa)) return directa;

        // The FIRST value is the original client; the rest are proxies.
        return xff.split(",")[0].trim();
    }

    private boolean isTrustedProxy(String ip) {
        // In the tests the previous hop is 127.0.0.1, which is on the list.
        return props.trustedProxies().stream().anyMatch(cidr -> coincide(ip, cidr));
    }

    /** CIDR prefix comparison. Enough for /8, /12, /16 and /32. */
    private boolean coincide(String ip, String cidr) {
        String base = cidr.split("/")[0];
        int bits = Integer.parseInt(cidr.split("/")[1]);
        int octetos = bits / 8;
        String[] a = ip.split("\\.");
        String[] b = base.split("\\.");
        if (a.length != 4 || b.length != 4) return false;
        for (int i = 0; i < octetos; i++) if (!a[i].equals(b[i])) return false;
        return true;
    }

    private String header(ServerWebExchange exchange, String nombre) {
        String v = exchange.getRequest().getHeaders().getFirst(nombre);
        return v == null ? "desconocido" : v;
    }
}
```

```java
package ar.edu.utn.frc.tup.p4.apigateway.ratelimit.impl;

import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.TokenBucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory bucket, per instance. A conscious decision: a distributed
 * limiter in Redis would add one write per request to the critical path, and
 * this limit is a flood guard - with N instances the real ceiling is
 * N × capacity, which is still a ceiling.
 */
@Component
public class InMemoryTokenBucket implements TokenBucket {

    private record Estado(double fichas, long ultimoNanos) { }

    private final Map<String, Estado> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean consume(String key, int capacidad, int recargaPorMinuto) {
        long ahora = System.nanoTime();
        Estado create = buckets.compute(key, (k, previo) -> {
            if (previo == null) return new Estado(capacidad - 1.0, ahora);
            double recargadas = (ahora - previo.ultimoNanos()) / 60_000_000_000.0 * recargaPorMinuto;
            double disponibles = Math.min(capacidad, previo.fichas() + recargadas);
            return new Estado(disponibles >= 1 ? disponibles - 1 : disponibles, ahora);
        });
        return create.fichas() >= 0;
    }

    @Override
    public Duration suggestedWait(String key) { return Duration.ofSeconds(60); }
}
```

- [ ] **Step 3: Escribir el filtro**

```java
package ar.edu.utn.frc.tup.p4.apigateway.filters;

import ar.edu.utn.frc.tup.p4.apigateway.config.properties.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.apigateway.constants.ErrorTypes;
import ar.edu.utn.frc.tup.p4.apigateway.ratelimit.*;
import ar.edu.utn.frc.tup.p4.apigateway.web.ProblemDetails;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Paso 9 del pipeline · @Order(8) · condicional.
 *
 * A no-op for every route that does not match `expensive-routes`. Startup FAILS
 * if an entry is configured with a threshold of 0: a route declared expensive
 * with zero budget rejects everything, which is worse than not configuring it.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimitProperties props;
    private final RateLimitKeyResolver resolve;
    private final TokenBucket bucket;

    public RateLimitFilter(RateLimitProperties props, RateLimitKeyResolver resolve, TokenBucket bucket) {
        this.props = props;
        this.resolve = resolve;
        this.bucket = bucket;
        validateThresholds();
    }

    private void validateThresholds() {
        if (props.expensiveRoutes() == null) return;
        props.expensiveRoutes().forEach(r -> {
            if (r.capacity() <= 0 || r.refillPerMinute() <= 0) {
                throw new IllegalStateException(
                        "La ruta cara '" + r.path() + "' tiene umbral 0: rechazaria todo. "
                        + "Configurar capacity y refill-per-minute, o sacarla de la lista.");
            }
        });
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.enabled() || props.expensiveRoutes() == null) return chain.filter(exchange);

        String path = exchange.getRequest().getPath().value();
        var cara = props.expensiveRoutes().stream()
                .filter(r -> MATCHER.match(r.path(), path)).findFirst();
        if (cara.isEmpty()) return chain.filter(exchange);

        var ruta = cara.get();
        String key = ruta.path() + "|" + resolve.resolve(exchange, ruta.key());

        if (bucket.consume(key, ruta.capacity(), ruta.refillPerMinute())) {
            return chain.filter(exchange);
        }
        // DEC-24 - the SAME type auth/ returns for its per-e-mail limit.
        return ProblemDetails.withRetryAfter(exchange, HttpStatus.TOO_MANY_REQUESTS,
                ErrorTypes.TOO_MANY_ATTEMPTS, "Demasiados intentos",
                "Superó el limite de solicitudes. Reintente mas tarde.",
                bucket.suggestedWait(key));
    }

    @Override public int getOrder() { return 80; }
}
```

- [ ] **Step 4: Correr y verify que pasa**

Run: `mvn -q test -Dtest=RateLimitForwardedIT`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: rate limit por IP real detras de proxy confiable (DEC-24)

El gotcha que no se manifiesta en desarrollo: getRemoteAddress() detras de un
balanceador da siempre la IP del balanceador, y el limitador trataria a todo
internet como un solo cliente. Y confiar en X-Forwarded-For sin verificar el
proxy deja evadirlo inventando una IP por request."
```

---
### Task 13: `PipelineOrderIT` — verify el orden real, no el declarado

**Files:**
- Create: `src/test/java/…/integration/PipelineOrderIT.java`
- Create: `src/test/java/…/support/FilterSequence.java`

**Interfaces:**
- Consumes: los ocho filtros (T7–T12), `SecurityConfig` (T6).
- Produces: la garantía de la que dependen `ServiceAudienceFilter` e `IdentityPropagationFilter`.

**Por qué es una tarea aparte:** el manifiesto lo pide explícitamente — *"confirmado **con pruebas de integración** el orden efectivo entre Spring Security y los `GlobalFilter`, **no asumirlo** por el orden declarado en código"*. Es el criterio de DoD #4, y es la premisa de la que cuelgan dos filtros.

- [ ] **Step 1: Escribir el registrador de secuencia**

```java
package ar.edu.utn.frc.tup.p4.apigateway.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared record of where a request went, in actual order. */
public final class FilterSequence {

    private static final List<String> PASOS = Collections.synchronizedList(new ArrayList<>());

    public static void register(String paso) { PASOS.add(paso); }
    public static List<String> pasos() { return List.copyOf(PASOS); }
    public static void clear() { PASOS.clear(); }

    private FilterSequence() { }
}
```

- [ ] **Step 2: Escribir el test (falla)**

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.filters.PrivateRouteGuard;
import ar.edu.utn.frc.tup.p4.apigateway.support.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PipelineOrderIT.FiltrosEspias.class)
class PipelineOrderIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    @BeforeEach
    void clear() { FilterSequence.clear(); }

    private void anAuthenticatedRequest() {
        UUID u = UUID.randomUUID();
        redis.opsForValue().set("session:" + u, "sid-1").block();
        cliente.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TokenFactory.persona(u, "sid-1"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void Spring_Security_corre_ANTES_que_TODOS_los_GlobalFilter() {
        // The premise of the whole design: if Security ran later, the guards
        // would have no validated Jwt to hang off.
        anAuthenticatedRequest();

        List<String> pasos = FilterSequence.pasos();
        assertThat(pasos.indexOf("security")).isLessThan(pasos.indexOf("orden-1"));
    }

    @Test
    void los_ocho_GlobalFilter_corren_en_el_orden_declarado() {
        anAuthenticatedRequest();

        assertThat(FilterSequence.pasos())
                .filteredOn(p -> p.startsWith("orden-"))
                .containsExactly("orden-1", "orden-2", "orden-3", "orden-4",
                                 "orden-5", "orden-6", "orden-7", "orden-8");
    }

    @Test
    void el_GATEWAY_ROUTE_ATTR_ya_esta_poblado_en_Order_6() {
        // THE check the spec asks for. ServiceAudienceFilter (@Order 6) cannot
        // compare aud against the destination if the attribute is not there.
        //
        // It SHOULD be: RoutePredicateHandlerMapping sets it when resolving the
        // handler, BEFORE the GlobalFilter chain starts. It is NOT set by
        // RouteToRequestUrlFilter (@Order 10000), which CONSUMES it to build the
        // destination URL. But that is a Spring Cloud Gateway internal, not a
        // contract guarantee: hence the check.
        anAuthenticatedRequest();
        assertThat(FilterSequence.pasos()).contains("ruta-resuelta-en-orden-6");
    }

    @Test
    void ServiceAudienceFilter_corre_ANTES_que_IdentityPropagationFilter() {
        // A non-negotiable invariant. The other way round, the destination would
        // receive identity headers injected from a token whose `aud` had not
        // been validated yet: anti-spoofing breaks.
        anAuthenticatedRequest();

        List<String> pasos = FilterSequence.pasos();
        assertThat(pasos.indexOf("orden-6")).isLessThan(pasos.indexOf("orden-7"));
    }

    @TestConfiguration
    static class FiltrosEspias {

        private static GlobalFilter espia(int orden) {
            return new GlobalFilter() {
                @Override public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
                    FilterSequence.register("orden-" + orden);
                    if (orden == 6 && ex.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) != null) {
                        FilterSequence.register("ruta-resuelta-en-orden-6");
                    }
                    if (orden == 1 && ex.getAttribute(PrivateRouteGuard.ATTR_JWT) == null) {
                        // Security already ran even if the attribute is not there
                        // yet: the @Order(4) guard sets it. See the other spy.
                        FilterSequence.register("jwt-aun-no-en-atributo");
                    }
                    return chain.filter(ex);
                }
                // The spies run IMMEDIATELY before the real filter of
                // ese orden, restando 1 al peso base.
                public int getOrder() { return orden * 10 - 1; }
            };
        }

        @Bean GlobalFilter espia1() { return espia(1); }
        @Bean GlobalFilter espia2() { return espia(2); }
        @Bean GlobalFilter espia3() { return espia(3); }
        @Bean GlobalFilter espia4() { return espia(4); }
        @Bean GlobalFilter espia5() { return espia(5); }
        @Bean GlobalFilter espia6() { return espia(6); }
        @Bean GlobalFilter espia7() { return espia(7); }
        @Bean GlobalFilter espia8() { return espia(8); }

        /** A Security `WebFilter` records that the reactive chain already ran. */
        @Bean
        org.springframework.web.server.WebFilter espiaSecurity() {
            return (exchange, chain) -> chain.filter(exchange)
                    .doFirst(() -> FilterSequence.register("security"));
        }
    }
}
```

> **Por qué los `@Order` son múltiplos de 10.** Los espías tienen que intercalarse **justo antes** de cada filtro real: con pesos `10, 20, …, 80`, un espía en `orden*10-1` cae siempre inmediatamente antes del suyo. Con pesos `1..8` no habría hueco y los espías quedarían todos al final. El orden **relativo** de los filtros reales es lo único que importa, así que la separación no cambia ningún comportamiento.

- [ ] **Step 3: Correr y verify**

Run: `mvn -q test -Dtest=PipelineOrderIT`
Expected: PASS — 4 tests.

🔴 **Si `el_GATEWAY_ROUTE_ATTR_ya_esta_poblado_en_Order_6` falla**, la salida **no** es subir el order de `ServiceAudienceFilter` por encima de 10000: eso lo pondría después de `IdentityPropagationFilter` y rompería el invariante del cuarto test. Habría que mover **los dos** por encima de 10000 conservando su orden relativo, y volver a correr los cuatro.

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/test/java
git commit -m "test: orden efectivo del pipeline verificado, no asumido (DoD #4)

El manifiesto lo pide explicito. Dos filtros dependen de que el atributo de
ruta ya este poblado y de que ServiceAudienceFilter corra antes que la
propagacion de identidad: son premisas, y ahora estan probadas."
```

---

### Task 14: Resiliencia — breaker, bulkhead y fallback

**Files:**
- Create: `src/main/java/…/config/ResilienceConfig.java`
- Test: `src/test/java/…/integration/ResilienceIT.java`

**Interfaces:**
- Consumes: `FallbackController` (T4).
- Produces: nada para tareas siguientes.

- [ ] **Step 1: Escribir el test (falla)** — criterio de DoD #8

```java
package ar.edu.utn.frc.tup.p4.apigateway.integration;

import ar.edu.utn.frc.tup.p4.apigateway.support.AbstractGatewayTest;
import ar.edu.utn.frc.tup.p4.apigateway.support.TokenFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

class ResilienceIT extends AbstractGatewayTest {

    @Autowired ReactiveStringRedisTemplate redis;

    private String token() {
        UUID u = UUID.randomUUID();
        redis.opsForValue().set("session:" + u, "sid-1").block();
        return TokenFactory.persona(u, "sid-1");
    }

    @AfterEach
    void restaurarDestino() {
        destino.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
        });
    }

    @Test
    void con_el_destino_caido_el_breaker_abre_y_responde_por_el_fallback() {
        destino.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(500);
            }
        });

        String t = token();
        // Fill the breaker's window (slidingWindowSize = 20).
        for (int i = 0; i < 25; i++) {
            cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + t).exchange();
        }

        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + t)
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.type").value(v ->
                        org.assertj.core.api.Assertions.assertThat((String) v)
                                .endsWith("/service-unavailable"));
    }

    @Test
    void un_destino_LENTO_corta_por_timeout_antes_que_el_cliente() {
        // DEC-42 - timeoutDuration 3 s < a browser's typical timeout. If the
        // gateway cut later, it would keep a thread busy for a response nobody
        // is going to read any more.
        destino.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(200)
                        .setBodyDelay(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        });

        cliente.get().uri("/api/users/me").header("Authorization", "Bearer " + token())
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void el_fallback_devuelve_ProblemDetail_no_una_pagina_de_error() {
        cliente.get().uri("/fallback/users-service")
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType("application/problem+json");
    }
}
```

- [ ] **Step 2: Escribir `ResilienceConfig`**

```java
package ar.edu.utn.frc.tup.p4.apigateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * DEC-42 - standard values are in place; calibration is a later adjustment, not
 * a prerequisite. A system with conservative thresholds is loosened by looking
 * at metrics; one with no limits has nowhere to start measuring.
 *
 * What to watch after the first load test: the 429 rate on legitimate traffic
 * (if > 0, loosen) and the breaker's open rate (if it opens without anything
 * being actually down, raise slidingWindowSize).
 */
@Configuration
public class ResilienceConfig {

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> porDefecto() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(20)          // ~1 s de trafico con 120 concurrentes:
                        .failureRateThreshold(50)       // reacciona rapido sin abrir por dos errores
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        // LOWER than the client's timeout: if the browser gives
                        // up at 5 s and the gateway at 10, the user sees a generic
                        // error and the gateway keeps a thread busy for nothing.
                        .timeoutDuration(Duration.ofSeconds(3))
                        .build())
                .build());
    }
}
```

Y add el filtro de circuit breaker a las rutas generadas, en el `application.yml`, bajo `spring.cloud.gateway.server.webflux.default-filters`:

```yaml
          default-filters:
            - name: CircuitBreaker
              args:
                name: porServicio
                fallbackUri: forward:/fallback/servicio
```

- [ ] **Step 3: Correr y verify que pasa**

Run: `mvn -q test -Dtest=ResilienceIT`
Expected: PASS — 3 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/main/resources/application.yml src/test/java
git commit -m "feat: breaker, timeout y fallback con ProblemDetail (DEC-42)

timeoutDuration 3s: menor que el timeout del cliente. Al reves el usuario ve
un error generico y el Gateway sigue ocupando un hilo por una respuesta que
ya nadie va a leer."
```

---

### Task 15: Empaquetado y verificación final

**Files:**
- Create: `Dockerfile`, `.dockerignore`
- Test: (todo el suite)

> **El `docker-compose.yml` NO es de esta tarea.** El compose del subsistema
> (`DEC-40`) se orquesta aparte y no vive en este repo. Lo que sí es de esta
> tarea es que el `Dockerfile` de acá funcione dentro de él sin tocarlo: imagen
> que arranca, puertos `expose` y nada de `ports:`.
>
> **Por qué importa el `expose` y no el `ports`.** Los micros no validan el JWT
> (`DEC-08`): confían en los headers `X-*` porque nadie puede alcanzarlos sin
> pasar por el Gateway. Con un puerto publicado esto funciona **sin password,
> sin token y sin 2FA**:
>
> ```
> curl -X DELETE localhost:8082/api/users/{id} -H "X-User-Roles: ADMIN"
> ```
>
> La ausencia de `ports:` ES el control de seguridad, no una preferencia de
> estilo. El criterio 1b del Step 4 lo verifica desde afuera.

**Interfaces:**
- Consumes: todas las tareas anteriores.
- Produces: el subsistema desplegable.

- [ ] **Step 1: Escribir el `Dockerfile`**

Mismo patrón que `users-service`:

```dockerfile
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
```

> **`USER app` antes del `COPY`, y `MaxRAMPercentage` en vez de `-Xmx`.** Lo
> primero porque un proceso root dentro del contenedor convierte un escape del
> JVM en root del host. Lo segundo porque `-Xmx` fijo ignora el límite del
> contenedor: con `--memory=512m` y `-Xmx1g` el kernel mata el proceso con un
> OOM que no deja ni un stack trace.

- [ ] **Step 2: Escribir el `.dockerignore`**

Sin esto el contexto de build sube `target/`, `.git/` y — lo grave —
`secrets/`: las claves RS256 terminarían **dentro de la imagen**.

```
target/
.git/
.gitignore
secrets/
.env
*.log
docs/
```

- [ ] **Step 3: Correr el suite completo**

Run: `mvn -q clean verify`
Expected: PASS.

Verificar además que **no hay `spring-boot-dependencies` por debajo de 4.1.1**
(`DEC-35`), que es el modo en que este proyecto se rompe en silencio:

Run: `mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot-dependencies`

- [ ] **Step 4: Verificar los criterios de DoD contra la spec**

Abrir `docs/SPEC-api-gateway.md` §15 y confirmar que cada criterio tiene su test:

| # | Test |
|---|---|
| 1 | `mvn dependency:tree` — el chequeo del Step 3, no un test |
| 1b | Check 0 del runbook de integración: con el stack arriba, `curl localhost:8082/actuator/health` **no responde** y `curl localhost:8080/actuator/health` sí. Se verifica desde afuera porque el compose no vive en este repo |
| 2 | `DiscoveryAllowlistIT` |
| 3 | `DiscoveryAllowlistIT` — el servicio fuera de la `include-expression` da 404 |
| 4 | `PipelineOrderIT` — **el orden efectivo, no el declarado** |
| 5 | `PublicPrivateRouteIT` |
| 6 | `ServiceAudienceIT` |
| 7, 7b | `SessionInvalidationIT` — key borrada → 401; Redis detenido → 503 + `Retry-After` |
| 7c | `IssuerValidationIT` |
| 7d, 7e | `AccountStateGuardIT` |
| 7f | `RateLimitForwardedIT` |
| 7g | `SessionCacheIT` |
| 8 | `ResilienceIT` |
| 9 | `LoggingFilterTest` + `CorrelationIdFilterTest` — y la comprobación de punta a punta la hace `scripts/regresion.sh` §5b, que cruza los logs de los dos servicios con un solo `X-Request-Id` |
| 10 | `IdentityPropagationIT` — **EL test de seguridad del Gateway** |
| 11 | `NoRoleAuthorizationTest` — cero `hasRole` / `hasAuthority` fuera de la cadena de Security |

> **Los criterios están cubiertos.** Si esta tabla y el §15 de la spec dejaran
> de coincidir, **la spec manda**: esta tabla es un índice, no la fuente.

- [ ] **Step 5: Commit final**

```bash
git add Dockerfile .dockerignore
git commit -m "build: Dockerfile multi-stage y .dockerignore

Usuario no-root y MaxRAMPercentage en vez de -Xmx fijo. El .dockerignore
excluye secrets/: sin el, las claves RS256 viajan dentro de la imagen."
```
