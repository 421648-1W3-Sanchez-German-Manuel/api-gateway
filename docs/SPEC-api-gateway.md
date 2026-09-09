# Especificación técnica de implementación — API Gateway

**Proyecto:** Plataforma Gamificada TUP · TPI · Tema 01 (Identidad y Usuarios) · UTN FRC
**Componente:** `api-gateway` (Spring Cloud Gateway · WebFlux)
**Fuente de verdad:** `manifiesto-api-gateway.html` (revisión v4 + parche v5) — LOCKEADO
**Referencias cruzadas:** `manifiesto-users-service.html` (v5), `manifiesto-flujos.html` (v5), `jwt-jwks-redis-explicado.html` (anexo), `TUP_PIV_BE_PROPUESTA_ARQ.pdf`
**Estado:** listo para generación de código. Las decisiones tomadas por el equipo para cerrar huecos de los manifiestos están en §14.0 (`DEC-01`…`DEC-28`, numeración compartida con `SPEC-users-service.md`); lo que sigue abierto está marcado `TODO` en el body y consolidado en §14.

> **Instrucción para el agente de generación de código.**
> Este documento es autocontenido: no hace falta volver a abrir los manifiestos HTML.
> Todo lo afirmado acá es decisión tomada, no sugerencia. Donde dice *"el Gateway NO hace X"*, no lo implementes aunque sea técnicamente posible.
> Donde dice `TODO`, **no asumas un value**: dejá el `TODO` visible en el código/config y seguí.
> Donde §14 marca una inconsistencia **sin** decisión asociada, **no la resuelvas**: implementá lo que dice este documento y dejá el `TODO` con la referencia a §14.
> Donde §14 marca una inconsistencia con un `DEC-xx`, esa es una **decisión ya tomada por el equipo** para cerrar un hueco de los manifiestos: implementala tal cual y dejá en el código el comentario `// DEC-xx`, para que se sepa que no salió de un manifiesto y hay que reflejarla de vuelta en la documentación.

---

## Índice

1. [Alcance y principios no negociables](#1-alcance-y-principios-no-negociables)
2. [Scaffolding: coordenadas del proyecto](#2-scaffolding-coordenadas-del-proyecto)
3. [Dependencias y versiones](#3-dependencias-y-versiones)
4. [Estructura de paquetes y archivos](#4-estructura-de-paquetes-y-archivos)
5. [Convención de rutas](#5-convención-de-rutas)
6. [Ruteo dinámico gobernado · DiscoveryLocatorConfig y allowlist](#6-ruteo-dinámico-gobernado--discoverylocatorconfig-y-allowlist)
   · **[6.5 Runbook: sumar un microservicio nuevo](#65-runbook--sumar-un-microservicio-nuevo-a-la-allowlist)**
7. [`application.yml` completo](#7-applicationyml-completo)
8. [Contrato de token](#8-contrato-de-token)
9. [El pipeline de 7 filtros](#9-el-pipeline-de-7-filtros)
10. [Headers de identidad y trazabilidad](#10-headers-de-identidad-y-trazabilidad)
11. [Autorización: lo que el Gateway NO hace](#11-autorización-lo-que-el-gateway-no-hace)
12. [Resiliencia, observabilidad y endpoints propios](#12-resiliencia-observabilidad-y-endpoints-propios)
13. [Convenciones de código y errores](#13-convenciones-de-código-y-errores)
14. [⚠️ Inconsistencias detectadas](#14--inconsistencias-detectadas)
15. [Definition of Done](#15-definition-of-done)

---

## 1. Alcance y principios no negociables

### 1.1 Qué es el Gateway

Única puerta de entrada al sistema. **Autentica, rutea de forma gobernada y propaga identidad enriquecida.** No conoce ningún dominio de negocio.

### 1.2 Reglas duras

| # | Regla | Origen |
|---|---|---|
| R1 | El Gateway es la única puerta de entrada. Ningún cliente accede a un microservicio por otro camino. | Gateway §01 · PDF §1.1 |
| R2 | Ningún microservicio le habla directo a otro. Toda llamada síncrona micro→micro vuelve a entrar por el Gateway. | Gateway §02 · PDF §1.1 |
| R3 | **El Gateway NO toma decisiones de rol.** Ni de persona, ni de servicio. Capa 1 (rol↔endpoint) y capa 2 (regla de negocio) viven enteras en `@PreAuthorize` + la lógica del microservicio destino. | Gateway §07 · users-service §07 |
| R4 | No existe el prefijo `/internal/**`. Se eliminó en v4. Lo que distingue una llamada de servicio es **el rol del token (`MS`)**, no la URL. | Gateway §00, §01 |
| R5 | El descubrimiento automático (Eureka) **no** implica exposición automática: hace falta opt-in explícito en la allowlist. | Gateway §04 |
| R6 | El Gateway no declara rutas a mano. Las genera `DiscoveryLocatorConfig` desde Eureka. | Gateway §04 |
| R7 | El path **no se reescribe**: el backend recibe exactamente el mismo path que entró. | Gateway §04 |
| R8 | Lo asincrónico (Kafka) **nunca** pasa por el Gateway. El Gateway solo rutea tráfico HTTP síncrono. | Flujos §01 · PDF §1.1 |
| R9 | `fetch-registry=true` **solo** en el Gateway. Los microservicios comunes van con `false`. | Gateway §10 |
| R10 | El Gateway hace **exactamente una** lectura a Redis por request de persona (validación de `sid`). Nunca escribe. Nunca lee otra cosa. | Gateway §03 p.1, §05.1 · users-service §04.1 |

### 1.3 Lo que el Gateway NO hace (lista cerrada)

- ❌ No autoriza por rol (ni `ADMIN`/`PROFESSOR`/`STUDENT`, ni `MS`).
- ❌ No valida el **value** del `scope` (solo lo propaga; validarlo es capa 2 del destino).
- ❌ No conoce entidades de negocio, ni base de datos relacional.
- ❌ No escribe en Redis.
- ❌ No reescribe paths (sin `RewritePath` por defecto).
- ❌ No loguea bodies ni tokens.
- ❌ No expone servicios de Eureka que no estén en la allowlist.
- ❌ No emite ni firma tokens (eso es del módulo `auth/` de `users-service`).
- ❌ No aplica `on_behalf_of` como permiso: es **solo** metadato de trazabilidad.

---

## 2. Scaffolding: coordenadas del proyecto

Valores para Spring Initializr (fijados por el equipo, no derivados de los manifiestos):

| Campo | Valor |
|---|---|
| Project | **Maven** |
| Language | **Java** |
| Java version | **21** |
| Spring Boot | **4.1.1** |
| Group | `ar.edu.utn.frc.tup.p4` |
| Artifact | `api-gateway` |
| Name | `api-gateway` |
| Package name | `ar.edu.utn.frc.tup.p4.apigateway` |
| Packaging | Jar |

> **Nota sobre el package.** El usuario indicó el prefijo `ar.edu.utn.frc.tu…` (truncado). Se resuelve como `ar.edu.utn.frc.tup.p4.apigateway`, que es (a) lo que Spring Initializr deriva de Group + Artifact, y (b) coherente con `users-service`, cuyo manifiesto §12 fija `ar.edu.utn.frc.tup.p4.usersservice`. Si el equipo quería otro package, es el único punto de este documento que hay que corregir a mano.

`spring.application.name` = **`api-gateway`** (Gateway §10). Puerto **8080**.

---

## 3. Dependencias y versiones

### 3.1 Versiones de plataforma

```
Java              21   (fijado por el equipo)
Maven             (wrapper mvnw incluido)
Spring Boot       4.1.1  (fijado por el equipo)
Spring Cloud      2025.1.3  "Oakwood"   ← ver TODO-01
```

> **TODO-01 · Pin exacto del release train de Spring Cloud.**
> El manifiesto (§12, DoD) dejaba *"fijar versión de Spring Boot / Spring Cloud"* como pendiente bloqueante. El equipo fijó **Spring Boot 4.1.1**; el train de Spring Cloud queda derivado, no dictado por el manifiesto.
> Dato verificado: el train **2025.1.x (Oakwood)** es el que acompaña a Spring Boot 4.x — `2025.1.2` (11-jun-2026) declara compatibilidad con Spring Boot 4.1.0, y `2025.1.3` (20-ago-2026) es el último publicado, construido contra Boot 4.0.8.
> **Acción para el agente:** usar `2025.1.3` como value inicial y **verificar el pin contra la [matriz oficial](https://spring.io/projects/spring-cloud/) antes del primer commit**. Si `mvn dependency:tree` muestra un downgrade de `spring-boot-dependencies` por debajo de 4.1.1, subir al primer `2025.1.x` que lo soporte. No inventar un train `2026.x`: al momento de write esta spec no existe uno publicado.

### 3.2 `pom.xml` — dependencias

| Artefacto | Para qué | Origen |
|---|---|---|
| `org.springframework.cloud:spring-cloud-starter-gateway-server-webflux` | Núcleo del Gateway (WebFlux). **Ojo con el nombre**: desde Spring Cloud Gateway 4.2 el starter es `…-gateway-server-webflux`, no el viejo `spring-cloud-starter-gateway`. | Gateway §00 |
| `org.springframework.boot:spring-boot-starter-oauth2-resource-server` | Validación JWT RS256 contra JWKS. **Es la única cadena que autentica** (Gateway §03 p.1). Trae `spring-boot-starter-security` transitivamente. | Gateway §03, §05 |
| `org.springframework.cloud:spring-cloud-starter-netflix-eureka-client` | Registro + `fetch-registry` para resolver `lb://`. | Gateway §10 |
| `org.springframework.cloud:spring-cloud-starter-loadbalancer` | Requerido por el discovery locator para resolver `lb://`. (Suele venir transitivo del starter de Eureka; declararlo explícito.) | derivado de R6/R7 |
| `org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j` | Circuit breaker + bulkhead + retry por `serviceId`. | Gateway §08 |
| `org.springframework.boot:spring-boot-starter-data-redis-reactive` | **Solo** la lectura de `session:{userId}`. Reactive porque el stack es WebFlux: un cliente bloqueante en el filtro de Security bloquea el event loop. | Gateway §03 p.1 · users-service §04.1 |
| `org.springframework.boot:spring-boot-starter-actuator` | Health liveness/readiness (los usa Eureka), métricas. | Gateway §09, §10 |
| `io.micrometer:micrometer-registry-prometheus` | Endpoint `/actuator/prometheus`. | Gateway §09 |
| `io.micrometer:micrometer-tracing-bridge-otel` | W3C Trace Context (`traceparent`) + trace id en el MDC. | Gateway §03 p.2, §06 |
| `org.springframework.boot:spring-boot-starter-validation` | Validación de las `@ConfigurationProperties` (allowlist, rate limit). | §13 |
| `org.springframework.boot:spring-boot-starter-test` (test) | — | — |
| `io.projectreactor:reactor-test` (test) | — | — |
| `org.testcontainers:junit-jupiter` + `:testcontainers` (test) | Redis + un stub de Eureka para las pruebas de integración del DoD. | §15 |
| `com.squareup.okhttp3:mockwebserver` (test) | Stub del JWKS y de los microservicios destino. | §15 |

**NO incluir:**
- ❌ `spring-boot-starter-web` — rompe WebFlux (dos stacks servlet/reactivo en el mismo classpath).
- ❌ `spring-boot-starter-data-jpa` / driver de MySQL — el Gateway no tiene base relacional.
- ❌ `spring-boot-starter-oauth2-authorization-server` — la emisión de tokens es 100 % de `auth/` en `users-service`, y ese starter trae un modelo de cliente/token que compite con el diseño (users-service §10).
- ❌ `spring-kafka` — R8: lo asincrónico no pasa por el Gateway.

---

## 4. Estructura de paquetes y archivos

```
api-gateway/
├── pom.xml
├── Dockerfile                              # TODO-02
├── docker-compose.yml                      # TODO-02
└── src/
    ├── main/
    │   ├── java/ar/edu/utn/frc/tup/p4/apigateway/
    │   │   ├── ApiGatewayApplication.java           # @SpringBootApplication + @EnableDiscoveryClient
    │   │   │
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java              # SecurityWebFilterChain, ReactiveJwtDecoder, JWKS
    │   │   │   ├── DiscoveryLocatorConfig.java      # allowlist + predicate /api/{nombre}/**
    │   │   │   ├── ResilienceConfig.java            # Resilience4j: breaker/bulkhead/retry por serviceId
    │   │   │   ├── RedisConfig.java                 # ReactiveStringRedisTemplate (solo lectura de sesión)
    │   │   │   └── properties/
    │   │   │       ├── GatewayRoutingProperties.java    # allowlist, sufijo de serviceId, prefijo /api
    │   │   │       ├── IdentityHeaderProperties.java    # firstNames de headers reservados
    │   │   │       └── RateLimitProperties.java         # rutas caras + umbrales  (TODO-03)
    │   │   │
    │   │   ├── security/
    │   │   │   ├── SessionValidator.java            # OAuth2TokenValidator<Jwt> — chequeo de sid (v5)
    │   │   │   ├── PrincipalContext.java            # record inmutable: identidad ya validada
    │   │   │   └── PrincipalContextFactory.java     # Jwt -> PrincipalContext
    │   │   │
    │   │   ├── filters/                             # los 7 GlobalFilter (el paso 1 es Spring Security)
    │   │   │   ├── CorrelationIdFilter.java         # @Order(1)
    │   │   │   ├── LoggingFilter.java               # @Order(2)
    │   │   │   ├── PublicRouteGuard.java            # @Order(3)
    │   │   │   ├── PrivateRouteGuard.java           # @Order(4)
    │   │   │   ├── AccountStateGuard.java           # @Order(5) · paso 6 · DEC-23 · no está en el manifiesto
    │   │   │   ├── ServiceAudienceFilter.java       # @Order(6) · paso 7 · DEC-04 · no está en el manifiesto
    │   │   │   ├── IdentityPropagationFilter.java   # @Order(7)
    │   │   │   └── RateLimitFilter.java             # @Order(8) · condicional
    │   │   │
    │   │   ├── ratelimit/
    │   │   │   ├── RateLimitKeyResolver.java        # interfaz (contrato)
    │   │   │   ├── TokenBucket.java                 # interfaz (contrato)
    │   │   │   └── impl/
    │   │   │       ├── PrincipalRateLimitKeyResolver.java
    │   │   │       └── InMemoryTokenBucket.java     # TODO-03
    │   │   │
    │   │   ├── repository/
    │   │   │   ├── SessionRepository.java           # interfaz (contrato) — §13 patrón Repository
    │   │   │   └── impl/
    │   │   │       └── RedisSessionRepository.java  # implementación
    │   │   │
    │   │   ├── web/
    │   │   │   ├── FallbackController.java          # forward:/fallback/{serviceId} -> 503 ProblemDetail
    │   │   │   └── GatewayErrorAttributes.java      # ProblemDetail uniforme para 401/403/404/429/503
    │   │   │
    │   │   └── constants/
    │   │       ├── IdentityHeaders.java             # constantes de firstNames de header
    │   │       └── PrincipalType.java               # enum { USER, SERVICE }
    │   │
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml
    │       └── logback-spring.xml                   # pattern con traceId/spanId en cada línea
    └── test/java/ar/edu/utn/frc/tup/p4/apigateway/
        ├── filters/…                                # unitarios por filtro
        └── integration/
            ├── PipelineOrderIT.java                 # DoD: orden efectivo Security vs GlobalFilter
            ├── DiscoveryAllowlistIT.java            # DoD: servicio fuera de allowlist -> 404
            ├── PublicPrivateRouteIT.java            # DoD: /public sin token OK, privado sin token 401
            ├── SessionInvalidationIT.java           # DoD: sid desactualizado -> 401; Redis caido -> 503 (DEC-01)
            ├── ServiceAudienceIT.java               # DoD: aud incorrecto -> 403 (DEC-04)
            └── ResilienceIT.java                    # DoD: breaker -> /fallback, rate limit -> 429
```

> **TODO-02 · `Dockerfile` y `docker-compose.yml`.** El manifiesto §12 los deja explícitamente como pendientes ("*Resultado final esperado, tentativo*"), con dos criterios ya fijados: **red privada** y **sin publicar los puertos de los micros** (§06). No inventar el resto (imagen base, healthchecks, límites) sin confirmar con el equipo.

---

## 5. Convención de rutas

### 5.1 Dos prefijos por servicio, ninguno más

| Property (en cada microservicio) | Valor | Uso |
|---|---|---|
| `app.api.public-path` | `/api/{nombre}/public` | Rutas sin JWT: login, registro, refresh, token de servicio, reset de password. |
| `app.api.private-path` | `/api/{nombre}` | Rutas con JWT obligatorio. Cubre **tanto persona como servicio** — lo que cambia es el rol del token, no el prefijo. |

Ejemplo real (`users-service`):

```properties
app.api.public-path=/api/users/public
app.api.private-path=/api/users
```

```java
@RestController
@RequestMapping("${app.api.public-path}/auth")   // nunca "/api/users/public/auth" hardcodeado
public class AuthController { /* POST /api/users/public/auth/login */ }
```

**Orden del segment `public` (decisión lockeada):** va `/api/{nombre}/public/**`, **no** `/api/public/{nombre}/**`. Motivo: mantiene `/api/{nombre}/**` como el único prefijo que el Gateway necesita conocer; `public` es un sufijo interno de cada servicio, no algo que el Gateway interprete al rutear.

### 5.2 Contrato de nombres, en un solo lugar

| Capa | Forma | Ejemplo |
|---|---|---|
| Repositorio | `tpi-{nombre}` | `tpi-users` — convención organizativa, **no participa del ruteo** |
| Eureka (`spring.application.name`) | `{nombre}-service` | `users-service` — **único** value del que parte `DiscoveryLocatorConfig` |
| Ruta externa (Gateway) | `/api/{nombre}/**` | `/api/users/**` — derivada del `serviceId`, nunca del nombre del repo |

Derivación exacta que implementa el Gateway:

```
serviceId de Eureka        "USERS-SERVICE"
  → lower-case             "users-service"
  → remove sufijo -service "users"
  → anteponer /api/        "/api/users/**"
```

### 5.3 La única excepción real

`GET /.well-known/jwks.json` — no lleva el prefijo `{nombre}` porque es una convención web estándar (RFC 8615), no propia del proyecto.

> ⚠️ **Ver §14 / INC-07:** los documentos se contradicen sobre si el Gateway *rutea* esa ruta o si los consumidores la piden directo a `users-service` por la red interna. Implementar según el manifiesto del Gateway (ruta pública, en la allowlist como excepción documentada) y dejar el `TODO` señalando la contradicción.

### 5.4 Rutas concretas que el Gateway va a ver (de `users-service`)

| Método + ruta | Acceso | Tipo de token esperado |
|---|---|---|
| `POST /api/users/public/auth/login` | público | ninguno |
| `POST /api/users/public/auth/2fa/verify` | público | ninguno |
| `POST /api/users/public/auth/refresh` | público | ninguno (el refresh va en el body) |
| `POST /api/users/public/auth/token` | público | ninguno (client credentials) |
| `POST /api/users/public/auth/password/reset` | público | ninguno (pedir el reset) |
| `POST /api/users/public/auth/password/reset/confirm` | público | ninguno (confirmar) · **DEC-16** |
| `POST /api/users/public/registration/student` | público | ninguno |
| `POST /api/users/public/registration/professor` | público | ninguno |
| `GET /api/users/public/registration/activate?token=…` | público | ninguno |
| `GET /.well-known/jwks.json` | público (excepción) | ninguno |
| `POST /api/users/auth/logout` | privado | `type: user` |
| `POST /api/users/auth/password/change` | privado | `type: user` |
| `GET /api/users/me` | privado | `type: user` |
| `PATCH /api/users/me/onboarding` | privado | `type: user` |
| `POST /api/users` | privado | `type: user` (rol `ADMIN` lo valida **users-service**) |
| `DELETE /api/users/{id}` | privado | `type: user` (rol `ADMIN` lo valida **users-service**) |
| `PATCH /api/users/{id}/role` | privado | `type: user` (rol `ADMIN` lo valida **users-service**) |
| `POST /api/users/whitelist` | privado | `type: user` (rol `ADMIN`) · `TODO-05` de la otra spec |
| `GET /api/users/whitelist` | privado | `type: user` (rol `ADMIN`) · `TODO-05` de la otra spec |
| `DELETE /api/users/whitelist/{id}` | privado | `type: user` (rol `ADMIN`) · `TODO-05` de la otra spec |
| `GET /api/users/profile/{id}` | privado | `type: service`, rol `MS` + `scope` (lo valida **users-service**) |

El Gateway **no** codifica esta tabla. Está acá solo como referencia de qué tráfico va a atravesar el pipeline — pero **tiene que espejar `SPEC-users-service.md` §14.1 y §14.2**: una referencia desactualizada es peor que ninguna, porque el generador la toma por buena.

---

## 6. Ruteo dinámico gobernado · `DiscoveryLocatorConfig` y allowlist

### 6.1 El principio

> Descubrimiento automático ≠ exposición automática.

Un servicio registrado en Eureka que **no** matchee la `include-expression` responde **404** a través del Gateway. No queda expuesto por default, ni parcialmente. Quedan excluidos por diseño: el propio `api-gateway`, `eureka-server`, y cualquier endpoint de infraestructura (Actuator de otros servicios).

Costo aceptado a propósito: el onboarding de un micro nuevo deja de ser cero-config. Es **nombre válido + opt-in explícito en la allowlist + contrato acordado + pruebas**. Con 12 equipos sumando servicios, el riesgo de exposición accidental pesa más que el ahorro de configuración.

### 6.2 Los cuatro pasos del locator

| Paso | Qué hace | Ejemplo |
|---|---|---|
| Service ID | Identificador lógico normalizado | `USERS-SERVICE` → `users` |
| Predicate | La URL entrante matchea contra el path | `Path=/api/users/**` |
| Load balancer | `lb://` resuelve instancia contra la caché local de Eureka | `lb://USERS-SERVICE` |
| Path enviado | El backend recibe **el mismo path**, sin reescritura | `GET /api/users/me` → el controller recibe `/api/users/me` |

**Regla:** `serviceId ≠ path`. El predicate compara la URL entrante; `lb://` resuelve la instancia, no cambia el contrato. Si en algún momento hace falta `RewritePath` para algo puntual, se documenta y se prueba el path final — **no se mezclan las dos estrategias en el mismo servicio.**

### 6.3 Consideraciones técnicas obligatorias

El snippet del manifiesto §04 es conceptual y **no funciona tal cual**. Tres correcciones necesarias, ninguna de las cuales cambia una decisión de diseño:

1. **Prefijo de properties.** En Spring Cloud Gateway 4.2+ (train 2025.1.x) las properties se movieron a `spring.cloud.gateway.server.webflux.*`; las viejas `spring.cloud.gateway.*` están deprecadas. Usar el prefijo nuevo.
2. **El predicate por defecto no sirve.** Por defecto el locator genera `Path=/{serviceId}/**` (→ `/users-service/**`) y además aplica un `RewritePath` que **strippea** ese prefijo. Ninguna de las dos cosas cumple R7 ni §5.2. Hay que sobrescribir `predicates` con una expresión SpEL que construya `/api/{nombre}/**`, y **dejar la lista `filters` vacía** para que no haya reescritura. Con el predicate correcto, quitar el `RewritePath` no produce el 404 que advierte la doc (esa advertencia aplica al predicate por defecto).
3. **`include-expression` y el case del `serviceId`.** `lower-case-service-id: true` afecta `serviceId` en predicates y filters. Escribir la allowlist de forma **case-insensitive y de match exacto** (ver §7), no con un `.contains()` sobre un string concatenado: `'USERS-SERVICE,…'.contains(serviceId)` es un match de *substring*, así que un servicio llamado `USERS` entraría por la puerta de atrás.

### 6.4 `DiscoveryLocatorConfig.java` — contrato

Clase `@Configuration` que:

- Enlaza `GatewayRoutingProperties` (`gateway.routing.*`): `allowlist: List<String>`, `service-id-suffix: "-service"`, `path-prefix: "/api"`.
- Valida en el arranque (`@PostConstruct` o `@Validated`) que la allowlist **no** contenga `api-gateway` ni `eureka-server`; si los contiene, **falla el arranque** con mensaje explícito.
- Expone un bean de utilidad `serviceIdToPathSegment(String serviceId)` que aplica la derivación de §5.2 y que reusan `PublicRouteGuard` y los tests.
- Declara **dos** rutas a mano — todo lo demás lo genera el locator:
  1. la del **fallback** del circuit breaker (§12);
  2. la del **JWKS** (`DEC-27`, abajo).

> 🔴 **`DEC-27` · El JWKS necesitaba una ruta declarada, y no la tenía.** §5.3 e INC-07 deciden que el Gateway *rutea* `GET /.well-known/jwks.json`; §7 la mete en `well-known-paths`, §9.1 le da `permitAll` y §9.4 la deja pasar. Pero el `DiscoveryLocatorConfig` **solo genera predicados `Path=/api/{nombre}/**`**: esa ruta no matchea ninguno. El request pasaba autenticación y los tres guards, y **moría en un 404 del propio Gateway**. La decisión estaba tomada; el mecanismo no existía. Se agrega la ruta estática del §7.
>
> No confundir con `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, que apunta **directo** a `users-service`: el Gateway no puede pasarse por sí mismo para conseguir la clave con la que valida. Esta ruta es para **los otros once micros**, que sí la piden a través del Gateway.

### 6.5 Runbook · sumar un microservicio nuevo a la allowlist

> **DEC-06 · La allowlist arranca con `users-service` y nada más.** Es el único servicio del subsistema con rutas HTTP confirmadas. `notifications-service` es externo y solo consume Kafka, así que **no necesita ruta** (R8). `mailing-service` **no entra** hasta cerrar INC-12.
> Cada servicio nuevo entra por **opt-in explícito**. Esta fricción es a propósito (§6.1).

#### 6.5.1 Checklist para pedirle al equipo que se quiere integrar

Mandarles esto tal cual. Sin las cinco respuestas, **no se agrega el servicio**.

| # | Qué pedirles | Por qué lo necesitás | Ejemplo de respuesta válida |
|---|---|---|---|
| 1 | El **`spring.application.name`** exacto con el que se registran en Eureka | Es el **único** value del que parte el ruteo. De acá sale la URL pública. | `cursos-service` |
| 2 | Confirmación de que respetan la convención **`{nombre}-service`** en minúsculas, con guiones | Si se registran como `CursosService` o `cursos`, la derivación de §5.2 genera un path que nadie va a adivinar | ✅ `cursos-service` · ❌ `srv-cursos`, `CursosService` |
| 3 | Que declaren sus **dos prefijos por properties**, no hardcodeados: `app.api.public-path=/api/cursos/public` y `app.api.private-path=/api/cursos` | Es el contrato de §5.1. Si hardcodean `/api/cursos` en el `@RequestMapping`, el día que cambie algo hay que tocar código | los dos properties en su `application.yml` |
| 4 | La **lista de rutas públicas** que van a exponer bajo `/public/**`, y confirmación de que **todo lo demás** exige JWT | `PublicRouteGuard` (§9.4) es control de exposición: hay que saber qué se está exponiendo a Internet sin token | `POST /api/cursos/public/code/validar` |
| 5 | Confirmación de que **NO usan `/internal/**`** ni ningún tercer prefijo | Se eliminó en la revisión v4. Si lo traen de un diseño viejo, se lo van a comer con un 404 | "confirmado, sin `/internal`" |

**Además, avisarles a ellos** (no es algo que te tengan que responder, es lo que tienen que saber):

- **Van a recibir estos headers ya validados** y pueden confiar en ellos: `X-Principal-Type`, `X-User-Id`/`X-Service-Id`, `X-User-Roles`/`X-Service-Scopes`, `traceparent`, `X-Request-Id`. Formato exacto en §10.1b.
- **La autorización por rol es de ellos**, no del Gateway (R3): `@PreAuthorize` en sus controllers, capa 1 + capa 2 (§11.2).
- **`fetch-registry: false`** en su `application.yml`. El Gateway es el único con `true` (R9).
- **`register-with-eureka: true`** y **Actuator con liveness/readiness**, porque Eureka usa el readiness real para el balanceo (§12.2).
- **No pueden llamar a otro micro directo** (R2): siempre por el Gateway.
- Si necesitan **datos de `users-service` sin persona detrás**, necesitan un **token de servicio**: pedir `clientId` + `clientSecret` al equipo de Identidad (trámite manual y único), guardarlo en **variable de entorno, nunca en el repo**, y pedir los scopes puntuales del catálogo de §11.6. Si el scope que necesitan no existe, se agrega uno específico — **nunca** uno genérico de más alcance. **El request de token lleva un campo `audience` obligatorio** (`DEC-17`, §11.5) con el `serviceId` del destino al que van a pegarle: sin él no se emite token, y con uno equivocado el Gateway responde **403**.
- Su servicio **no queda expuesto** por registrarse en Eureka. Hasta que no esté en la allowlist, responde **404** a través del Gateway.

#### 6.5.2 Pasos del lado del Gateway, una vez que respondieron

1. Agregar el `serviceId` a **los dos lugares** del `application.yml` (§7) — están duplicados a propósito, uno para el locator y otro tipado para los guards y los tests:
   ```yaml
   spring.cloud.gateway.server.webflux.discovery.locator.include-expression: >
     {'users-service','cursos-service'}.contains(serviceId.toLowerCase())

   gateway.routing.allowlist:
     - users-service
     - cursos-service
   ```
2. **Verificar que no sea infraestructura.** `DiscoveryLocatorConfig` falla el arranque si aparece `api-gateway` o `eureka-server`; el resto (Actuator de terceros, sidecars) es criterio humano.
3. Levantar el servicio contra el Gateway y confirmar los **tres** comportamientos:
   - una ruta pública suya responde **sin token**;
   - la misma ruta fuera de `/public/**` **sin token** da **401**;
   - el **path final** que recibe su controller es idéntico al que entró (sin reescritura, R7).
4. Agregar un caso a `DiscoveryAllowlistIT` con ese `serviceId`.
5. Registrar el alta en el CHANGELOG del Gateway: **quién** pidió el opt-in, **cuándo**, y **qué rutas públicas** declaró. Es el registro de qué se expuso a Internet y por decisión de quién.

#### 6.5.3 Errores típicos al integrar (para anticiparlos)

| Síntoma | Causa casi siempre |
|---|---|
| **404** en todo, a través del Gateway | El servicio no está en la allowlist, o su `spring.application.name` no coincide con lo declarado |
| **404** solo en algunas rutas | El servicio no respeta `/api/{nombre}/**`: registró `cursos-service` pero expone `/cursos/...` sin el `/api` |
| El controller recibe un path **sin** `/api/{nombre}` | Alguien agregó un `RewritePath`. Viola R7 — sacarlo |
| **401** en rutas que ellos creen públicas | Pusieron `public` en otra posición: es `/api/cursos/public/**`, no `/api/public/cursos/**` (§5.1) |
| **403** con token de servicio válido | El `aud` no corresponde a este destino (§9.7 / DEC-04), o el destino exige un `scope` que no pidieron |

---

## 7. `application.yml` completo

```yaml
# ============================================================
# api-gateway · Plataforma Gamificada TUP · Tema 01
# ============================================================
spring:
  application:
    name: api-gateway

  cloud:
    gateway:
      server:
        webflux:
          # --- Ruteo dinámico gobernado (Gateway §04) -------------------
          discovery:
            locator:
              enabled: true
              lower-case-service-id: true

              # ALLOWLIST · opt-in explícito (DEC-06). Un servicio registrado
              # registered in Eureka that does not match here answers 404 through the gateway.
              # Match EXACTO y case-insensitive (no substring). Ver §6.3.
              # Para sumar un servicio nuevo: seguir el runbook de §6.5.
              # NO agregar mailing-service sin cerrar INC-12.
              include-expression: >
                {'users-service'}
                .contains(serviceId.toLowerCase())

              # PREDICATE - /api/{name}/**  ({name} = serviceId without "-service")
              predicates:
                - name: Path
                  args:
                    pattern: >
                      '/api/' + serviceId.toLowerCase().replace('-service','') + '/**'

              # FILTERS · vacío A PROPÓSITO: el path NO se reescribe (R7).
              # NO agregar RewritePath acá. Ver §6.2 y §6.3.
              filters: []

          # ----------------------------------------------------------
          # DEC-27 - the only static route besides the fallback (§6.4).
          # The locator only generates Path=/api/{name}/**; without this the
          # JWKS pasa Security y los guards y muere en un 404 del Gateway.
          # ----------------------------------------------------------
          routes:
            - id: jwks
              uri: lb://users-service
              predicates:
                - Path=/.well-known/jwks.json
              filters: []        # el path se reenvía tal cual (RFC 8615)

          # --- Headers que el Gateway NUNCA deja pasar from afuera -----
          # Defensa en profundidad: IdentityPropagationFilter (§9.8) los
          # strips them anyway, but this cuts them before any filter runs.
          # ⚠ traceparent NO va acá: se acepta el entrante (W3C, §9.2).
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
          # JWKS de users-service. El Gateway cachea las claves por kid y
          # re-consulta ante un kid desconocido (§8.4).
          # DEC-28 · users-service en 8082 (su management en 8083).
          # El 8081 es el puerto de management DE ESTE Gateway: reusarlo
          # acá chocaba corriendo ambos en localhost para desarrollo.
          jwk-set-uri: ${JWKS_URI:http://users-service:8082/.well-known/jwks.json}
          # DEC-07 - `iss` = "users-service" (the service's logical name, same
          # criterion as a service token's `aud`). It is NOT a URL, so
          # que se valida con un JwtClaimValidator<String> propio, NO con
          # `issuer-uri` is not used (Spring treats it as a URL and does OIDC discovery).
          # Ver SecurityConfig §9.1.
          # RS256 es el único algoritmo admitido (Gateway §03 p.1)
          jws-algorithms: RS256

  data:
    redis:
      # Única infraestructura de status que toca el Gateway: lectura de
      # session:{userId} for the single session (v5). It never writes.
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 500ms
      lettuce:
        pool:
          # TODO-03 · sin calibrar contra RF-NFR-03 (120 concurrentes)
          max-active: 16
          max-idle: 8
          min-idle: 2

server:
  port: 8080

# ============================================================
# Eureka (Gateway §10)
# ============================================================
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
    register-with-eureka: true
    # The ONLY component in the system with fetch-registry=true: it is the only
    # que resuelve lb://. Los micros comunes van con false (R9).
    fetch-registry: true
    healthcheck:
      enabled: true          # Eureka usa el status real de Actuator, no solo el heartbeat
  instance:
    prefer-ip-address: true

# ============================================================
# Config propia del Gateway
# ============================================================
gateway:
  jwt:
    expected-issuer: users-service     # DEC-07 · se valida siempre (DEC-44)

  # DEC-25 · caché local de session:{userId}. TTL corto a propósito:
  # redefine "invalidación inmediata" de 0 s a <=3 s (§9.1.2).
  session-cache:
    ttl: 3s
    max-size: 10000

  routing:
    # A typed mirror of the include-expression allowlist, for the guards
    # y los tests. Mantener ambas listas sincronizadas.
    allowlist:
      - users-service
    service-id-suffix: "-service"
    path-prefix: "/api"
    # Public routes that do NOT follow the /api/{name}/public/** convention
    well-known-paths:
      - /.well-known/jwks.json      # ver §5.3 y §14 / INC-07

  identity:
    # Headers reservados: se borran del request entrante SIEMPRE, y se
    # inyectan solo con valores validados (§10).
    reserved-headers:
      - X-Principal-Type
      - X-User-Id
      - X-Service-Id
      - X-User-Roles
      - X-Service-Scopes

  rate-limit:
    enabled: true
    # DEC-24 · la ruta de auth SÍ es cara y su bucket va por IP.
    # Without this, DoD criterion 7f cannot pass (the filter would be a no-op).
    expensive-routes:
      - path: /api/users/public/auth/**
        key: IP
        capacity: 30          # TODO-03 · orden de magnitud, sin calibrar
        refill-per-minute: 30 # TODO-03
    # TODO-03 - the remaining expensive routes are not enumerated in any
    # manifest. This default applies only when an entry is added without
    # capacity propio; con 0, el arranque falla (ver §9.9).
    default-bucket:
      capacity: 0             # TODO-03
      refill-per-minute: 0    # TODO-03

# ============================================================
# Resilience4j (Gateway §08)
# Orden de las capas: rate limit → bulkhead → timeout → retry → breaker → fallback
# ============================================================
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 20              # DEC-42
        failureRateThreshold: 50           # DEC-42
        waitDurationInOpenState: 10s       # DEC-42
        permittedNumberOfCallsInHalfOpenState: 3
  timelimiter:
    configs:
      default:
        timeoutDuration: 3s                # DEC-42 · < que el timeout del cliente
  bulkhead:
    configs:
      default:
        maxConcurrentCalls: 64             # DEC-42 · > los 120 concurrentes / 2 destinos
  retry:
    configs:
      # Retry SOLO en operaciones idempotentes (GET o con idempotency key).
      idempotent:
        maxAttempts: 3
        waitDuration: 200ms
        enableExponentialBackoff: true
        enableRandomizedWait: true         # backoff + jitter

# ============================================================
# Observabilidad (Gateway §06, §09)
# ============================================================
management:
  server:
    port: ${MANAGEMENT_PORT:8081}   # red/puerto de management, NO expuesto a Internet
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true               # /actuator/health/liveness y /readiness
      show-details: never
  tracing:
    propagation:
      type: w3c                     # traceparent
    sampling:
      probability: 1.0              # TODO-03

logging:
  pattern:
    # The trace id comes out on EVERY line, without each filter logging it by hand.
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-},%X{requestId:-}]"
```

> **DEC-07 · `iss` = `users-service`.** Ningún documento fijaba el value del claim `issuer` (INC-11); el equipo lo cerró: **`iss: "users-service"`**, el nombre lógico del micro, mismo criterio que ya usa el `aud` del token de servicio.
> **Consecuencia de contrato:** `users-service` tiene que **emitir** ese claim en ambos tipos de token — hoy no aparece en los ejemplos de §05 de ningún manifiesto. Agregarlo al contrato de token de `users-service` §04 antes de que el Gateway active la validación, o todos los tokens van a fallar con 401.
> **Consecuencia técnica:** `users-service` no es una URL, así que **no** se usa `spring.security.oauth2.resourceserver.jwt.issuer-uri` (Spring lo interpreta como URL y dispara discovery OIDC contra ella). Se registra un `JwtClaimValidator<String>` propio en el `ReactiveJwtDecoder` (§9.1).

---

## 8. Contrato de token

Dos tipos de token, ambos firmados **RS256** por el módulo `auth/` de `users-service`, ambos validados por el Gateway contra el **mismo JWKS**.

### 8.1 Token de persona (`access`)

```jsonc
{
  "iss":   "users-service",  // DEC-07 · OBLIGATORIO · validado siempre (§9.1)
  "sub":   "a3f1c2e4-...",   // userId (UUID)
  "roles": ["STUDENT"],       // STUDENT | PROFESSOR | ADMIN
  "type":  "user",
  "jti":   "b7d9...",
  "sid":   "f0a2...",        // session id · v5
  "est":   "ACTIVE",         // DEC-23 · estado_cuenta · lo lee §9.6
  "pwd":   false,            // DEC-23 · debe_cambiar_password
  "onb":   false,            // DEC-23 · primer_login
  "iat":   1730000000,
  "exp":   1730000600        // ~10 minutos
}
```

### 8.2 Token de servicio (`role MS`, enriquecido v4)

```jsonc
{
  "iss":           "users-service",       // DEC-07 · lo emite users-service, no el cliente
  "sub":           "cursos-service",      // quién pide
  "roles":         ["MS"],                // role EXCLUSIVO de comunicación micro↔micro
  "type":          "service",
  "aud":           "users-service",       // para quién es válido · DEC-17
  "scope":         "users.profile.read",  // qué recurso puntual puede pedir
  "on_behalf_of":  null,                  // userId si delega; si no, null
  "exp":           1730000300             // ~5 minutos
}
```

> **Estos dos ejemplos son el contrato canónico y tienen que coincidir con `SPEC-users-service.md` §10.1 y §10.2, claim por claim.** El token de servicio **no** lleva `est`/`pwd`/`onb` (no representa una persona) ni `sid` (es stateless).

| Claim | Quién lo valida | Qué hace el Gateway |
|---|---|---|
| firma (RS256, `kid`) | Gateway | valida contra JWKS; 401 si falla |
| `exp` | Gateway | 401 si expiró |
| `iss` | Gateway | 401 si `!= "users-service"` (**DEC-07**) |
| `type` | Gateway | determina `X-Principal-Type` |
| `sid` (solo `type: user`) | Gateway | compara contra `session:{sub}` en Redis; 401 si no coincide |
| `est` / `pwd` / `onb` (solo `type: user`) | Gateway | **403** fuera de `/api/users/**` si la cuenta no está habilitada (**DEC-23**, §9.6). **401** si faltan en un token de persona |
| `aud` (solo `type: service`) | Gateway | **403** si no coincide con el `serviceId` destino resuelto (**DEC-04**, §8.5 y §9.7) |
| `roles` | **microservicio destino** | el Gateway solo lo copia a un header |
| `scope` | **microservicio destino** (capa 2) | el Gateway **solo lo propaga**, nunca valida su value |
| `on_behalf_of` | nadie autoriza con esto | metadato de trazabilidad — **ver §8.6** |

### 8.3 Rol `MS` — semántica exacta

- `MS` es un **rol**, value dentro del array `roles`, exactamente igual que `STUDENT`/`PROFESSOR`/`ADMIN`.
- Es **exclusivo de comunicación entre microservicios** y **nunca asignable a una persona**.
- El Gateway **no** decide nada con él: solo lo propaga. Quien exige `hasRole('MS')` es el `@PreAuthorize` del microservicio destino.
- Los tokens con rol `MS` son **100 % stateless**: no llevan `sid`, no se chequean contra Redis. La sesión única es un concepto de persona.

### 8.4 JWKS · caché y refresh

- El Gateway consulta `/.well-known/jwks.json` al arrancar y cachea las claves **por `kid`**.
- **Disparador 1 (obligatorio):** re-fetch reactivo ante un token con un `kid` desconocido. Es el mecanismo que hace posible rotar la clave sin downtime — sin él, el día que `users-service` rote, el Gateway rechaza todos los tokens nuevos. `NimbusReactiveJwtDecoder` con `jwk-set-uri` ya lo implementa.
- **Disparador 2 (defensa extra):** revalidación periódica cada **12–24 h** aunque no aparezca un `kid` nuevo, por caché corrupta o fetch fallido silencioso.
- Obtener la clave pública es un `GET` **sin autenticación**: la clave pública no es secreta. No confundir con pedir un token de servicio, que sí requiere credenciales.

### 8.5 Validación de `aud` — contrato y hueco

**Contrato (lockeado, Gateway §05.2 y DoD §12):** *"El Gateway rechaza un token de servicio cuyo `aud` no coincide con el destino real — evita que un token emitido para hablar con Cursos se reuse contra Usuarios."*

**El problema técnico que ningún documento resolvía (INC-06):** el `OAuth2TokenValidator<Jwt>` de Spring Security corre en la `SecurityWebFilterChain`, **antes** de que Spring Cloud Gateway resuelva la ruta y el `serviceId` destino. Un audience validator estático no puede hacer una comparación *por destino*: no sabe todavía a dónde va el request.

> **DEC-04 · Se resuelve con un filtro dedicado post-routing: `ServiceAudienceFilter`.**
> Corre **después** de que el Gateway resolvió la ruta, así que compara el claim `aud` contra el **`serviceId` destino realmente resuelto** — no contra una derivación del path. Es la lectura literal de *"el destino real"* del DoD.
> **Costo aceptado:** el pipeline pasa de 7 a **8 pasos** (y a 9 con `DEC-23`). Es un componente que **no está en el manifiesto §03** y hay que reflejarlo de vuelta en él. Ver el contrato completo en §9.7.
>
> **Nomenclatura:** en todo el documento, un filtro se identifica por su **número de paso del pipeline** (§9) — `AccountStateGuard` es el **paso 6**, `ServiceAudienceFilter` el **paso 7**. No usar "8º/9º filtro": esa numeración venía de contarlos como agregados sobre los 7 del manifiesto y nombra distinto al mismo componente.

`ServiceAudienceValidator` **no existe** como `OAuth2TokenValidator`: la validación entera vive en el filtro (§9.7).

### 8.6 `on_behalf_of` — trazabilidad, nunca permisos

- Es **opcional**: `null` cuando el job actúa como sistema puro.
- Cuando un job actúa en nombre de una persona (ej. un recálculo nocturno disparado por una acción de un `ADMIN`), queda registrado **para auditoría**.
- **No le da a ese job los permisos de esa persona.** El Gateway no lo mezcla con `X-User-Id`, no lo convierte en un rol, y no lo usa en ninguna decisión.
- Un token con `type: service` es **siempre** `X-Principal-Type: service`, tenga o no `on_behalf_of`.

**Cómo llega al destino (DEC-03 + DEC-10):** el set de headers de §06 del manifiesto **no incluye ningún header para `on_behalf_of`** (INC-05). El Gateway **reenvía el `Authorization` original** (§10.4, `DEC-03`), así que el claim viaja hasta el destino y está disponible para quien quiera leerlo.

> ⚠️ **`DEC-10` (spec de `users-service`) limita el alcance de esto.** `users-service` decidió **no parsear el JWT** en absoluto (`DEC-08`): su `Authentication` sale solo de los headers `X-*`. O sea que, para el destino más importante del sistema, `on_behalf_of` **efectivamente no llega** — queda solo en los logs del Gateway, correlacionable por `traceparent`.
> **Consecuencia:** los eventos de auditoría de `users-service` no pueden decir en nombre de quién actuó un servicio. Auditar eso exige cruzar logs. Se aceptó conscientemente; ver `SPEC-users-service.md` §7.5.

El Gateway lo loguea siempre en `LoggingFilter` (§9.3) — con `DEC-10`, ese log pasa de ser una comodidad a ser **el único registro** del dato.

> **NO crear un header `X-On-Behalf-Of`.** No existe en el contrato de §06 y agregarlo sería inventar superficie que otros 11 equipos tendrían que soportar.

---

## 9. El pipeline de 7 filtros — que son 9

Todo request atraviesa esta cadena, en este orden.

> **Son 9 pasos, no 7.** El manifiesto §03 documenta 7. Se agregan dos, y los dos hay que reflejarlos de vuelta en el manifiesto:
>
> - `AccountStateGuard` (§9.6) por **DEC-23** — el gate de cuenta sobre rutas de otros micros, que hoy `manifiesto-flujos` §11 dibuja como imposible.
> - `ServiceAudienceFilter` (§9.7) por **DEC-04** — la validación de `aud`, que en los 7 originales no tenía dónde vivir.
>
> **`AccountStateGuard` y `ServiceAudienceFilter` son mutuamente excluyentes** (uno solo mira `type: user`, el otro solo `type: service`), así que el orden relativo entre ellos es indiferente. Lo que **no** es indiferente es que ambos vayan después de la autenticación y antes de `IdentityPropagationFilter`.

> **Advertencia lockeada del manifiesto (§03):** Spring Security y los `GlobalFilter` de Spring Cloud Gateway corren en **cadenas distintas** de WebFlux. El orden descripto acá es el **orden intencional**; el **orden efectivo se tiene que confirmar con pruebas de integración** antes de confiar en él (§15, `PipelineOrderIT`). No asumirlo por el orden declarado en código.

| # | Componente | Mecanismo | Order |
|---|---|---|---|
| 1 | Autenticación | `SecurityWebFilterChain` (Spring Security) | cadena aparte |
| 2 | `CorrelationIdFilter` | `GlobalFilter` | `@Order(1)` |
| 3 | `LoggingFilter` | `GlobalFilter` | `@Order(2)` |
| 4 | `PublicRouteGuard` | `GlobalFilter` | `@Order(3)` |
| 5 | `PrivateRouteGuard` | `GlobalFilter` | `@Order(4)` |
| 6 | `AccountStateGuard` **(DEC-23)** | `GlobalFilter` | `@Order(5)` |
| 7 | `ServiceAudienceFilter` **(DEC-04)** | `GlobalFilter` | `@Order(6)` |
| 8 | `IdentityPropagationFilter` | `GlobalFilter` | `@Order(7)` |
| 9 | `RateLimitFilter` | `GlobalFilter` | `@Order(8)` · condicional |

---

### 9.1 Paso 1 — Autenticación · `SecurityConfig` / `SecurityWebFilterChain`

**Responsabilidad:** la **única** cadena que autentica. Nada más autentica en todo el Gateway.

**Contrato:**

| Entrada | `ServerWebExchange` con (o sin) header `Authorization: Bearer <jwt>` |
|---|---|
| Salida OK | `SecurityContext` con un `JwtAuthenticationToken` poblado |
| Salida error | **401** emitido por Spring Security, body `ProblemDetail` (§13) |

**Qué valida, en orden:**

1. **Firma RS256** contra la clave pública del JWKS, buscada por `kid` (§8.4). Algoritmo permitido: **solo `RS256`** — rechazar cualquier otro, incluido `none`.
2. **`iss`** — debe ser exactamente `users-service` (**DEC-07**). Se valida **siempre** (**DEC-44**, §9.1.0), con un `JwtClaimValidator<String>` y **no** con `issuer-uri` (§7).
3. **`exp`** (`JwtTimestampValidator`, con el skew por defecto).
4. **(v5) `sid` contra Redis**, **solo para `type: user`**: lee `session:{sub}`; si el value no coincide con el claim `sid` → **401 sesión superada**. Es la **única lectura de Redis de todo el Gateway**. **No aplica** a `type: service`.

**`aud` no se valida acá**: no se puede, porque el destino todavía no está resuelto. Vive en `ServiceAudienceFilter` (§9.7, **DEC-04**).

#### 9.1.0 Los claims nuevos y el orden de integración · **DEC-44**

`iss` (`DEC-07`) y `est`/`pwd`/`onb` (`DEC-23`) **no salen de ningún manifiesto**: los definimos nosotros. Eso plantea una pregunta de orden — ¿qué pasa si el Gateway los valida antes de que `users-service` los emita?

**`DEC-44` · Se validan siempre, sin flag. La red de seguridad es un test, no un modo de despliegue.**

> 🔴 **Nota de diseño: `DEC-19` y `DEC-43` quedan RETIRADAS.** Versiones anteriores de esta spec resolvían esto con un flag de configuración (`expected-issuer` vacío) y después con un switch de tres estados (`off`/`warn`/`enforce`). **Eran sobreingeniería**, y conviene dejar escrito por qué para que nadie las reintroduzca:
>
> Ese patrón — activar una validación nueva en modo observación y después endurecerla — existe para sistemas **en producción, con tráfico real y tokens viejos en circulación**. Acá no hay nada de eso: los dos servicios son del mismo equipo, ninguno está desplegado, no hay usuarios ni tokens emitidos. Se estaba protegiendo una transición desde un estado que no existe.
>
> Y el escenario de falla que decía prevenir no se sostiene: si `users-service` no está escrito, **no arranca** — no hay endpoint de login, no hay token, no hay `401` misterioso. Para que exista un token **sin** `iss`, alguien tiene que haber implementado la emisión ignorando un claim que §10.1 marca como obligatorio. Eso es un bug común, y los bugs se agarran con tests, no con variables de entorno.

**Las dos cosas que sí resuelven el problema:**

**1. Un test que no deja pasar el bug** — `TokenContractTest`, del lado de `users-service`, afirma que **todo** token emitido lleva los claims obligatorios de su tipo. Si alguien olvida uno, falla en CI con el nombre del claim, antes de que nadie despliegue nada.

**2. Un `401` diagnosticable.** Cuando el Gateway rechaza un token, el log dice **exactamente por qué**:

```
WARN JWT_RECHAZADO reason=claim-ausente  claim=iss                                    requestId=<id>
WARN JWT_RECHAZADO reason=claim-invalido claim=iss esperado=users-service recibido=<v> requestId=<id>
WARN JWT_RECHAZADO reason=claim-ausente  claim=est                                    requestId=<id>
```

Esto es lo que convierte "todo devuelve 401 y no sé por qué" en diez segundos de `grep`. El **body** de la respuesta no lleva ese detalle: a un atacante no se le explica qué le faltó al token. Va solo al log.

> **Por qué no un validador tolerante** (aceptar `iss` ausente **o** correcto): sería un agujero permanente, y un token **sin** `iss` es exactamente el que fabricaría un atacante.

**El orden de integración**, que es todo lo que queda del asunto: `users-service` emite los cuatro claims **desde su primer commit** — están en el contrato de §10.1, son parte de emitir un token, no un paso aparte. Y como el Gateway no puede hacer nada sin `users-service` (ni siquiera conseguir el JWKS), no existe una ventana donde uno esté sin el otro.

La implementación queda así:

```java
@Bean
ReactiveJwtDecoder jwtDecoder(GatewayProperties props, SessionValidator sessionValidator) {
    var validators = List.<OAuth2TokenValidator<Jwt>>of(
            new JwtTimestampValidator(),
            new JwtClaimValidator<String>(
                    JwtClaimNames.ISS, props.jwt().expectedIssuer()::equals),
            sessionValidator);                              // §9.1.1

    var decoder = NimbusReactiveJwtDecoder
            .withJwkSetUri(jwkSetUri).jwsAlgorithm(RS256).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
}
```

Sin condicionales ni modos. Lo que sí hay que implementar es que el rechazo **loguee la causa** — ahí estaba el value real.

**Configuración de rutas:**

```java
.authorizeExchange(ex -> ex
    .pathMatchers("/api/*/public/**").permitAll()
    .pathMatchers("/.well-known/**").permitAll()          // §5.3 · ver §14/INC-07
    .pathMatchers("/actuator/health/**").permitAll()      // puerto de management
    .pathMatchers("/fallback/**").permitAll()
    .anyExchange().authenticated())
.oauth2ResourceServer(o -> o.jwt(...))
.csrf(ServerHttpSecurity.CsrfSpec::disable)              // API stateless, sin cookies
.httpBasic(disable).formLogin(disable)
```

> **`.hasRole(...)` / `.hasAuthority(...)` están PROHIBIDOS en esta configuración.** R3: el Gateway no autoriza por rol. Todo lo privado es `authenticated()`, nada más.

**Implementación de la validación de `sid`** — `SessionValidator implements OAuth2TokenValidator<Jwt>`, registrado en el `ReactiveJwtDecoder`:

- Si `type != "user"` → `OAuth2TokenValidatorResult.success()` (los tokens de servicio no llevan `sid`).
- Si `type == "user"`: `sessionRepository.findSid(userId)` → comparar con el claim `sid`.
- El cliente Redis debe ser **reactivo** (`ReactiveStringRedisTemplate`): un cliente bloqueante acá bloquea el event loop de Netty.

**Clave Redis:** `session:{userId}` → `sid`. Sin TTL propio. **El Gateway solo lee. Nunca escribe, nunca borra.**

#### 9.1.1 Fail-mode de la validación de `sid` — **DEC-01 + DEC-02**

Ningún documento definía esto (INC-02, INC-03). Decisión del equipo: **fail-closed, distinguiendo la causa.**

| Situación | Significado | Respuesta del Gateway |
|---|---|---|
| `session:{userId}` existe y **coincide** con el claim `sid` | sesión vigente | continúa el pipeline |
| `session:{userId}` existe y **no coincide** | **sesión superada** por un login más nuevo en otro dispositivo | **401** · `type: session-superseded` |
| `session:{userId}` **no existe** | **logout previo** — el logout de `auth/` borra la key (**DEC-02**) | **401** · `type: session-closed` |
| Redis **no responde**: timeout, connection refused, pool agotado | falla de infraestructura | **503** + **`Retry-After`** · `type: session-store-unavailable` |

**Reglas de implementación:**

- El timeout de la operación es el de `spring.data.redis.timeout` (500 ms, §7). Si vence → **503**, nunca 401: un 401 le diría al frontend "volvé al login", que es la acción **equivocada** cuando el problema es que Redis está caído.
- **Nunca fail-open.** Si Redis no responde, el Gateway **no** deja pasar el request validando solo firma + `exp`. El trade-off está aceptado explícitamente en `users-service` §04.1: *"la autenticación completa depende de que Redis esté arriba"*.
- Emitir una **métrica separada** por cada rama (`gateway.session.check{result=match|superseded|closed|unavailable}`). Es lo que permite, durante la demo, distinguir en 5 segundos "Redis se cayó" de "todos perdieron la sesión".
- Loguear la rama `unavailable` a nivel **`ERROR`** con el trace id; las otras tres a `INFO`.

> **DEC-02 · El logout borra `session:{userId}`.** Los tres documentos decían cosas distintas (INC-02). El equipo cerró: **`auth/` borra la key en el logout.** Efecto secundario deseado: el logout ahora corta el access token **al instante**, sin esperar los ~10 min de `exp` — el costo de esa lectura a Redis ya está pagado por la sesión única.
> **Consecuencia asumida:** si Redis se reinicia y pierde todo, cada request de persona ve "key ausente" y recibe 401 — indistinguible de un logout. Es aceptable porque el 503 solo cubre el caso en que Redis **no responde**, no el caso en que responde vacío. Documentarlo en el runbook operativo.
> **Consecuencia de contrato:** `users-service` tiene que **agregar el borrado de la key al flujo de logout**, que hoy `manifiesto-flujos` §05 no incluye.

---

#### 9.1.2 Caché local de `session:{userId}` · **DEC-25**

La revisión v5 puso **una lectura a Redis por cada request de persona**. Eso contradice la justificación de `manifiesto-flujos` §05 (*"sin martillar Redis"*, INC-04), pero la contradicción **no es la que parece**:

- **No es un problema de carga.** 120 usuarios concurrentes, aun a 10 req/s cada uno, son ~1.200 GET/s. Un Redis single-node hace del orden de 100.000 ops/s: está dos órdenes de magnitud lejos de ser cuello de botella. La frase del manifiesto está mal **por el motivo que da**, pero el diseño aguanta el pico de examen sin problema.
- **Sí es un problema de disponibilidad.** Con `DEC-01` fail-closed, **Redis caído = plataforma caída**: `503` para toda persona. Antes de v5 eso no pasaba. Redis pasó a ser el SPOF más crítico del sistema.

**Mitigación:** caché local en memoria del Gateway (Caffeine) de `session:{userId} → sid`, con **TTL de 3 segundos** y tamaño máximo acotado.

- Corta ~90 % de las lecturas bajo carga sostenida.
- Da una ventana acotada en la que un hipo de Redis no dispara `503` a todo el mundo.
- `DEC-01` **sigue aplicando** ante una caída real: pasado el TTL sin respuesta, fail-closed.
- Es **caché de proceso, no compartida**: cada instancia del Gateway tiene la suya. No agrega un componente nuevo ni un modo de falla nuevo.

> ⚠️ **Esto redefine "invalidación inmediata" de v5: de 0 s a ≤3 s.** El texto de la decisión v5 dice *"sin esperar a que el access token viejo expire (podía tardar hasta 10 min)"* — 3 segundos honra el espíritu con holgura, pero **es una desviación del texto de una decisión lockeada**, tomada a conciencia. Si se prefiere cero desvío, la alternativa es no cachear y poner solo un circuit breaker sobre la llamada a Redis, que hace el `503` rápido en vez de lento pero no lo evita.

🔧 **Actualizar `manifiesto-flujos` §05**, cuya justificación quedó invalidada por el parche v5 y hoy le da a un lector una impresión errónea de la arquitectura.

---

### 9.2 Paso 2 — `CorrelationIdFilter` · `@Order(1)`

**Responsabilidad:** correlación y tracing. Es el primer `GlobalFilter`.

**Contrato:**

| Entrada | header `traceparent` (opcional), header `X-Request-Id` (opcional) |
|---|---|
| Salida | ambos garantizados en el exchange, y `traceId`/`spanId`/`requestId` en el **MDC** |

**Comportamiento:**

1. `traceparent`: **acepta el entrante** si viene bien formado (W3C Trace Context) — es el caso de una llamada micro→micro, donde el trace ya empezó. Si no viene o es inválido, **genera uno nuevo** (primer salto).
2. `X-Request-Id`: propaga el entrante o genera un UUID nuevo.
3. Pone `traceId` (y `spanId`, `requestId`) en el **MDC** de logging. A partir de acá el *pattern* de logging de la app los expone en cada línea automáticamente — **ningún otro filtro loguea el trace id a mano**.
4. En WebFlux el MDC no se propaga solo entre operadores: usar el contexto de Reactor (`contextWrite` + hook de Micrometer `ContextSnapshot`), no un `ThreadLocal` pelado.

`traceparent` **no** está en la lista de headers a strippear: es un dato de trazabilidad legítimamente entrante, no un header de identidad.

---

### 9.3 Paso 3 — `LoggingFilter` · `@Order(2)`

**Responsabilidad:** registrar el tráfico, con el trace id ya en el MDC.

**Contrato:**

| Loguea | método, ruta, status, duración (ms), `serviceId` destino resuelto |
|---|---|
| **Nunca loguea** | **bodies**, **tokens**, headers `Authorization`, ni el `clientSecret` de `/auth/token` |

- Un log al entrar (nivel `DEBUG`) y uno al salir con status + duración (nivel `INFO`), vía `chain.filter(exchange).doFinally(...)`.
- Mensajes **estructurados y consistentes** entre filtros (§13).
- Si el token trae `on_behalf_of`, loguearlo acá (§8.6): es el único lugar donde ese metadato de trazabilidad tiene destino garantizado.

---

### 9.4 Paso 4 — `PublicRouteGuard` · `@Order(3)`

**Responsabilidad:** **control de exposición, no de rol.** Protege contra que algo termine siendo público sin querer.

**Contrato:**

| Confirma | (a) que `/api/*/public/**` sea efectivamente la **única** familia de rutas sin token, salvo las `well-known-paths` declaradas; (b) que el path esté dentro de la **allowlist gobernada** (§6) |
|---|---|
| Salida OK | continúa la cadena |
| Salida error | **404** si el `{nombre}` del path no está en la allowlist; **401** si un path fuera de `/public/**` (y fuera de `well-known-paths`) llegó sin `Authentication` |

**Algoritmo:**

1. Extraer `{nombre}` de `/api/{nombre}/…`. Si el path no matchea `/api/**` y tampoco está en `gateway.routing.well-known-paths` → dejar pasar (no es tráfico ruteado; el 404 lo emite el propio Gateway más adelante).
2. Si `{nombre}` no corresponde a ningún servicio de la allowlist → **404**, sin filtrar información sobre qué servicios existen.
3. Si el path contiene `/public/` en una posición distinta de la del segundo segment (`/api/{nombre}/public/**`) → **404**. Esto corta `/api/users/foo/public/bar` y variantes que intenten simular la familia pública.

> **No implementa `permitAll`**: eso ya lo hace Security (paso 1). Este guard es la mitad simétrica de `PrivateRouteGuard`: cubren fallas distintas del mismo problema (Security corriendo en una cadena separada de los `GlobalFilter`), y **no son redundantes**.

---

### 9.5 Paso 5 — `PrivateRouteGuard` · `@Order(4)`

**Responsabilidad:** **defensa en profundidad** contra autenticación fallida silenciosa.

**Contrato:**

| Para | todo lo que **no** matchea `/api/*/public/**` ni `well-known-paths` ni `/fallback/**` |
|---|---|
| Confirma | que exista un `Authentication` **ya resuelto por Security** en el `SecurityContext`, autenticado, y que su principal sea un `Jwt` |
| Salida error | **401** — corta acá, en vez de llegar a propagar identidad vacía |

**Por qué existe:** si por un bug de configuración (o por el orden entre las dos cadenas de WebFlux) algo privado entrara sin autenticar, este filtro lo corta antes de que `IdentityPropagationFilter` inyecte headers vacíos que el destino confiaría ciegamente.

Además valida la **coherencia mínima del token** antes de propagar:
- `type` presente y ∈ `{user, service}`; si no → **401**.
- `type: user` → `sub` presente y `roles` no vacío.
- `type: service` → `sub` presente y `roles` contiene `MS`.

> Esto **no es autorizar**: no compara el rol contra la ruta. Es verificar que el token esté bien formado antes de convertirlo en headers que otro componente va a confiar sin chequear.

---

### 9.6 Paso 6 — `AccountStateGuard` · `@Order(5)` · **DEC-23 · no está en el manifiesto**

**Responsabilidad:** impedir que una persona con la cuenta **no habilitada** llegue a rutas de *otros* microservicios. Es el gate **grueso**; los tres gates finos siguen dentro de `users-service` (`DEC-14`).

**Por qué acá y no en cada micro:** `manifiesto-flujos` §11 dibuja `GET /api/cursos/mis-cursos → users-svc·users/ → 403 ONBOARDING_PENDING`. **Ese carril es imposible:** esa ruta va a `cursos-service`, y `users-service` no ve ese tráfico. Las alternativas eran que cada uno de los once micros implementara el mismo chequeo (inconsistente, y hay que convencer a once equipos) o que consultaran a `users-service` por request (un salto HTTP por request por servicio). Ver `SPEC-users-service.md` §18 / INC-18.

**Contrato:**

| Se aplica | solo a `type: user`. Un token de servicio **no atraviesa este filtro** — no representa una persona con cuenta |
|---|---|
| Entrada | claims `est`, `pwd`, `onb` del token (`SPEC-users-service.md` §10.1, **DEC-23**) |
| Condición de bloqueo | `est != "ACTIVE"` **o** `pwd == true` **o** `onb == true` |
| Si bloquea | permite **solo** `/api/users/**` y `/api/*/public/**`. Cualquier otro path → **403** |
| Cuerpo del error | `ProblemDetail` (§13) con `type` según la causa: `pending-account` (+ `accountStatus`), `password-change-required`, `onboarding-pending` — **los mismos `type` que devuelve `users-service`**, para que el frontend tenga una sola rama de manejo |
| Claims ausentes | si `type: user` y falta alguno de los tres → **401**. Es un token emitido por una versión vieja de `users-service`; ver la nota de secuencia abajo |

**La regla completa, en una línea:**

> Si el principal es persona y la cuenta no está habilitada, **solo se permiten `/api/users/**` y `/api/*/public/**`**.

**Por qué el Gateway no necesita una lista de rutas exentas por servicio:** porque **todas** las rutas exentas de los tres gates finos (`GET /api/users/me`, `PATCH /api/users/me/onboarding`, `POST /api/users/auth/password/change`) son de `users-service`. El Gateway deja pasar todo `/api/users/**` y delega el detalle fino al destino, que ya lo tiene implementado. Ninguna ruta de otro micro es exenta, por definición.

> **Esto no viola `R3`.** `R3` dice que el Gateway no autoriza **por rol**, y no lo hace: no mira `roles` ni compara rol contra ruta. El estado de una cuenta no es un rol — es la diferencia entre "quién sos" y "si tu cuenta está habilitada para operar".

> 🔴 **Riesgo de secuencia, igual que `DEC-07`.** Los claims `est`/`pwd`/`onb` **hoy no existen**. Si este filtro se activa antes de que `users-service` los emita, toda persona recibe `401`.
>
> **Se resuelve igual que el de `iss` (`DEC-44`, §9.1.0): sin flag.** Los claims están en el contrato de token de §10.1 como obligatorios, `TokenContractTest` no deja emitir un token sin ellos, y si igual falta uno el rechazo loguea `JWT_RECHAZADO reason=claim-ausente claim=est`. No hace falta un modo de despliegue para un bug que un test agarra en CI.

---

### 9.7 Paso 7 — `ServiceAudienceFilter` · `@Order(6)` · **DEC-04 · no está en el manifiesto**

**Responsabilidad:** rechazar un token de servicio emitido para **otro destino**. Evita que un token emitido para hablar con Cursos se reuse contra Usuarios.

**Contrato:**

| Se aplica | **solo** cuando `type == "service"`. Para `type: user` y para rutas públicas, pasa sin hacer nada. |
|---|---|
| Compara | el claim `aud` contra el **`serviceId` destino ya resuelto por el Gateway** |
| Salida OK | continúa la cadena |
| Salida error | **403** · `type: audience-mismatch` (no 401: el token es **auténtico**, simplemente no es para este destino) |

**Por qué corre acá y no en Spring Security:** en `@Order(5)` la ruta ya está resuelta, así que el `serviceId` real está disponible en el exchange. En la cadena de Security no lo estaría (§8.5).

**Algoritmo:**

```java
// 1. Route resolved by the gateway (put there by RouteToRequestUrlFilter / the handler mapping)
Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
// 2. destination serviceId: the host of the lb://USERS-SERVICE URI
String targetServiceId = route.getUri().getHost().toLowerCase();   // "users-service"
// 3. the token's aud (it may be a list: it only has to CONTAIN the destination)
List<String> aud = jwt.getAudience();
// 4. an empty aud, or one that does not contain targetServiceId -> 403
```

**Reglas:**

- Si un token de servicio llega **sin `aud`** → **403**. `manifiesto-users-service` §04.1 es explícito: *"`aud` y `scope` no son opcionales"*.
- La comparación es **case-insensitive** y contra el `serviceId` de Eureka normalizado a minúsculas (`users-service`), **no** contra el segment del path (`users`). Coincide con el value que `users-service` §04 usa en sus ejemplos.
- El filtro **no mira el `scope`**: sigue siendo capa 2 del destino (R3).
- Si `GATEWAY_ROUTE_ATTR` es `null` (el request no se va a rutear a ningún micro: `/fallback/**`, Actuator), el filtro **pasa sin validar** — no hay destino contra el cual comparar.

> **Verificación obligatoria en `PipelineOrderIT`:** confirmar empíricamente que a `@Order(6)` el atributo `GATEWAY_ROUTE_ATTR` ya está poblado.
>
> **Debería estarlo:** lo pone `RoutePredicateHandlerMapping` al resolver el handler, **antes** de que arranque la cadena de `GlobalFilter` — no lo pone `RouteToRequestUrlFilter` (`@Order(10000)`), que lo *consume* para armar la URL destino. O sea que cualquier order de esta cadena lo ve poblado. Verificar igual: es un detalle interno de Spring Cloud Gateway, no una garantía del contrato.
>
> 🔴 **Si el test fallara, la salida NO es subir el order por encima de 10000.** Eso pondría este filtro **después** de `IdentityPropagationFilter` (`@Order(7)`), y §9 declara no negociable que corra antes: el destino recibiría los headers de identidad inyectados de un token cuyo `aud` todavía no se validó — se rompe el anti-spoofing, que es justo lo que el filtro existe para sostener. La salida correcta sería mover **los dos** (`ServiceAudienceFilter` y `IdentityPropagationFilter`) por encima de 10000, conservando su orden relativo.

---

### 9.8 Paso 8 — `IdentityPropagationFilter` · `@Order(7)`

**Responsabilidad:** entregar al microservicio destino el set de identidad **ya validada**.

**Contrato — el orden de las dos operaciones es parte del contrato:**

1. **PRIMERO borra** del request **todos** los headers con los nombres reservados (`gateway.identity.reserved-headers`), vengan de donde vengan. **Anti-spoofing.** Se borran **siempre**, incluso en rutas públicas donde no hay token que inyectar.
2. **DESPUÉS inyecta** el set completo de identidad validada (§10), derivado **únicamente** del `Jwt` que Security ya verificó.

```java
exchange.mutate().request(r -> {
    IdentityHeaders.RESERVED.forEach(r::headers /* remove */);
    // ... then r.header(...) with the validated values
}).build();
```

**Nunca** copiar un value desde el request entrante a un header de identidad. La única fuente es el `Jwt` validado.

**Fuente de cada header:** ver la tabla de §10.

---

### 9.9 Paso 9 — `RateLimitFilter` · `@Order(8)` · condicional

**Responsabilidad:** limitar el abuso en rutas caras.

**Contrato:**

| Se aplica | **solo** en rutas marcadas como caras (`gateway.rate-limit.expensive-routes`). En el resto, pasa sin hacer nada. |
|---|---|
| Algoritmo | **token bucket** |
| Clave | resuelta por `RateLimitKeyResolver`: por **usuario** (`X-User-Id`), por **servicio** (`X-Service-Id`) o por **IP** (rutas públicas, sin principal), según corresponda |
| Rutas caras iniciales | **`/api/users/public/auth/**`, por IP** (`DEC-24`) |
| Excedido | **429** + header **`Retry-After`** |

> **`DEC-42` · Valores estándar puestos; la calibración es un ajuste, no un requisito previo.** Cierra `TODO-03` y `TODO-09`.
>
> El manifiesto §08 dejaba todo sin calibrar contra RF-NFR-03 (120 concurrentes, pico de "modo examen"), y la versión anterior de esta spec dejaba `expensive-routes: []` con umbrales en `0` **mientras exigía** el criterio de DoD 7f. Con la lista vacía el filtro es no-op y **ese test no podía pasar nunca**.
>
> **Decisión: arrancar con valores razonables y medir después.** Un sistema con umbrales conservadores puestos se puede aflojar mirando métricas; uno sin límites no tiene de dónde empezar a medir.
>
> | Parámetro | Valor | De dónde sale |
> |---|---|---|
> | Rate limit `/api/users/public/auth/**` | **30 req/min por IP** | Un alumno legítimo hace ~3 requests de auth por sesión (login, 2FA, refresh). 30 deja margen de 10× para reintentos y varias personas detrás de un mismo NAT |
> | Rate limit de login en `auth/` | **5 fallos / 15 min por email** | `DEC-24`. Cuenta **fallos**, no intentos: quien acierta no consume presupuesto |
> | `slidingWindowSize` | 20 | Con 120 concurrentes, 20 llamadas es ~1 s de tráfico: el breaker reacciona rápido sin abrirse por un pico de dos errores |
> | `failureRateThreshold` | 50 % | Estándar de Resilience4j |
> | `waitDurationInOpenState` | 10 s | Suficiente para que un pod reinicie o un GC largo pase |
> | `timeoutDuration` | **3 s** | 🔴 **tiene que ser menor que el timeout del cliente.** Si el navegador corta a los 5 s y el Gateway a los 10, el usuario ve un error genérico y el Gateway sigue ocupando un hilo por una respuesta que ya nadie va a leer |
> | `maxConcurrentCalls` (bulkhead) | **64** | Más que los 120 concurrentes repartidos entre destinos, y menos que "sin límite": el bulkhead existe para que un destino lento no consuma todo el pool |
> | Pool Lettuce | 16/8/2 | Con la caché de `DEC-25` (TTL 3 s) las lecturas reales a Redis bajan ~90 %. 16 conexiones sobran |
>
> **Qué sigue siendo cierto:** el filtro es no-op para toda ruta que no matchee `expensive-routes`, y **falla el arranque** si hay una entrada con umbral `0`.
>
> **Cuándo revisarlos:** después de la primera prueba de carga real. Lo que hay que mirar es la tasa de `429` sobre tráfico legítimo (si es > 0, aflojar) y la de apertura del breaker (si abre sin que el destino esté caído, subir `slidingWindowSize`).

`RateLimitKeyResolver` y `TokenBucket` van como **interfaces en `ratelimit/`** con las implementaciones en `ratelimit/impl/` (§13).

#### 9.9.1 Reparto con el rate limit de `auth/` · **DEC-24**

INC-15 los trataba como un conflicto de dueños. **No lo son: limitan cosas distintas y ninguno puede hacer el trabajo del otro.**

| | Gateway | `auth/` (`users-service`) |
|---|---|---|
| Clave | **IP de origen** | **email de la cuenta** |
| Qué ve | la ruta. No parsea el body: **no sabe qué cuenta se está atacando** | la cuenta. Ya pagó el viaje a la base |
| Qué frena | inundación / DoS | fuerza bruta sobre una cuenta concreta |
| Umbral | generoso (orden de 30 req/min/IP) · `TODO-03` | estricto (orden de 5 fallos / 15 min) · `TODO-09` de la otra spec |

El Gateway es el único que puede cortar **antes** del round-trip a la base y del BCrypt — que es caro **a propósito** (~100 ms). 120 logins basura concurrentes son un DoS real que `auth/` no puede prevenir, porque para cuando decide ya lo pagó.

Como las **claves son distintas**, los dos limitadores no pueden dispararse por la misma condición: el que salta primero responde.

**El `type` compartido es `https://tpi.utn.frc/errors/too-many-attempts`**, con `429` y `Retry-After` en los dos lados. Estaba prometido como "el mismo `type`" pero **ninguna de las dos specs decía cuál era**; queda fijado acá y replicado en `SPEC-users-service.md` §17.2. El frontend tiene una sola rama de manejo y no necesita saber quién contestó; se distinguen en logs y métricas.

> 🔴 **Gotcha obligatorio.** La clave por IP tiene que salir del `X-Forwarded-For` vía `ForwardedHeaderTransformer`, con una lista explícita de proxies confiables. Una implementación ingenua toma la IP del balanceador y **limita a todo internet como si fuera un solo cliente**: el limitador queda inútil y **nadie se entera hasta el pico de examen**. Debe haber un test que presente dos `X-Forwarded-For` distintos detrás del mismo proxy y verifique que consumen buckets separados.

---

## 10. Headers de identidad y trazabilidad

> Lo que el microservicio destino **puede confiar ciegamente**.

| Header | Cuándo aparece | Valor | Fuente (claim) |
|---|---|---|---|
| `X-Principal-Type` | **siempre** (en request autenticado) | `user` \| `service` | `type` |
| `X-User-Id` | `X-Principal-Type: user` | UUID de la persona | `sub` |
| `X-Service-Id` | `X-Principal-Type: service` | `sub` del servicio llamador | `sub` |
| `X-User-Roles` | `X-Principal-Type: user` | lista separada por coma sin espacio: `STUDENT` / `PROFESSOR` / `ADMIN` | `roles` |
| `X-Service-Scopes` | `X-Principal-Type: service` | lista separada por coma sin espacio: `MS` **+** el `scope` del token | `roles` + `scope` |
| `traceparent` | **siempre** | W3C Trace Context — propagado o generado en el primer salto | `CorrelationIdFilter` |
| `X-Request-Id` | **siempre** | id de request, para correlacionar logs además del trace | `CorrelationIdFilter` |

### 10.1 Reglas de inyección

- **`X-User-*` y `X-Service-*` son mutuamente excluyentes.** Un request `service` **no** lleva `X-User-Id` ni `X-User-Roles`, ni siquiera cuando `on_behalf_of` tiene value (§8.6).
- Los headers de identidad se inyectan **solo** en requests autenticados. En rutas públicas se **borran** (paso 1 de §9.8) y no se inyecta ninguno — el destino, al no ver `X-Principal-Type`, sabe que es tráfico público.
- `traceparent` y `X-Request-Id` se inyectan **siempre**, incluso en rutas públicas.

### 10.1b Formato exacto — **DEC-05**

El manifiesto §06 describía estos valores en prosa, sin separador (INC-08). Decisión del equipo: **coma sin espacio, y `MS` incluido dentro de `X-Service-Scopes`**, que es la lectura literal del *"`MS` + el scope del token"*.

```http
# request de persona
X-Principal-Type: user
X-User-Id: a3f1c2e4-0b7d-4e21-9c88-5f0a1b2c3d4e
X-User-Roles: STUDENT

# request de servicio
X-Principal-Type: service
X-Service-Id: cursos-service
X-Service-Scopes: MS,users.profile.read
```

**Reglas de serialización (las cumple el Gateway; el destino puede confiar en ellas):**

- Separador: **`,`**, **sin espacios** alrededor. Del lado del destino, un solo `split(",")` sirve para ambos headers.
- **Sin valores vacíos**, sin coma final, sin duplicados.
- Orden en `X-Service-Scopes`: **primero los roles** (`MS`), después los scopes. Un parser correcto no debe depender del orden, pero el Gateway lo emite estable.
- Valores en **mayúsculas para roles** (`STUDENT`, `MS`) y **tal cual vienen del claim para scopes** (`users.profile.read`, en minúsculas con puntos).
- Si `roles` viniera vacío, el request ya fue cortado con 401 por `PrivateRouteGuard` (§9.5) — el header nunca se emite vacío.

**Del lado de `users-service`** (y de cualquier micro destino), el mapeo a `GrantedAuthority` es: cada value de `X-User-Roles` → `ROLE_<value>`; en `X-Service-Scopes`, `MS` → `ROLE_MS` y el resto → authorities planas (sin prefijo), de modo que `hasRole('MS')` y la verificación de scope de capa 2 funcionen sobre el mismo `Authentication`.

### 10.2 Confiabilidad — la condición de red

> Estos headers son confiables **únicamente** porque la red bloquea que un cliente le hable directo a un microservicio.
> **Docker:** red privada, sin publicar los puertos de los micros.
> **Kubernetes:** `ClusterIP` + `NetworkPolicy`.
> **Si esa frontera de red no está, ningún header es confiable** — por eso `IdentityPropagationFilter` primero borra lo que venga puesto desde afuera.

### 10.3 Trazabilidad de punta a punta

El `traceparent` generado o propagado en el paso 2 viaja en este set hasta el microservicio, y de ahí a cualquier llamada siguiente. **El logging pattern de cada servicio (Gateway y micros) debe incluir el trace id en cada línea** — así un problema se sigue de punta a punta con un solo id, sin cruzar logs a mano.

### 10.4 Header `Authorization`

**DEC-03 · El Gateway REENVÍA el `Authorization: Bearer` original al microservicio destino.**

Ningún documento lo decía (INC-05). El equipo lo cerró a favor de reenviarlo, que además es el **comportamiento por defecto de Spring Cloud Gateway**: no hay que hacer nada, y **no** hay que agregar `RemoveRequestHeader=Authorization`.

Qué habilita:

- El **zero-trust interno** que describe `jwt-jwks-redis` §04: cada micro puede validar el token por su cuenta contra el mismo JWKS, sin depender ciegamente de la frontera de red.
- Que `on_behalf_of` llegue al destino sin inventar un header (§8.6).
- Que un micro pueda **reenviar el mismo token** hacia otro micro (caso "a" de §11.4) sin tener que reconstruirlo.

Qué **no** cambia:

- Los headers `X-*` siguen siendo la fuente que el destino usa para autorizar. **No** es "el destino revalida y decide": el destino puede validar como defensa en profundidad, pero el contrato de §10 sigue siendo el mismo.
- El Gateway **sigue borrando** los headers de identidad entrantes (§9.8). Reenviar el `Authorization` no relaja el anti-spoofing: el token viene firmado, los headers `X-*` no.
- `LoggingFilter` **nunca** loguea el `Authorization` (§9.3).

---

## 11. Autorización: lo que el Gateway NO hace

### 11.1 Confirmación explícita

**El Gateway NO toma decisiones de rol.** Ni de persona (`ADMIN`/`PROFESSOR`/`STUDENT`), ni de servicio (`MS`). Cada microservicio recibe el rol **ya verificado** en `X-User-Roles` o `X-Service-Scopes` y decide con `@PreAuthorize`.

En el código del Gateway esto se traduce en prohibiciones concretas:

- ❌ Ningún `.hasRole(...)`, `.hasAuthority(...)` ni `.access(...)` con lógica de rol en `SecurityConfig`.
- ❌ Ningún `if (roles.contains("ADMIN"))` en ningún filtro.
- ❌ Ninguna tabla ruta→rol en configuración.
- ❌ El Gateway **no valida el value del `scope`** — solo lo propaga.

### 11.2 Dónde vive la autorización: las dos capas, en el destino

| Capa | Pregunta | Dónde vive | Ejemplo |
|---|---|---|---|
| **Capa 1 · rol↔endpoint** | ¿Este rol puede llamar esta ruta? | `@PreAuthorize` en el controller del microservicio destino | `GET /api/users/profile/{id}` → `@PreAuthorize("hasRole('MS')")` |
| **Capa 2 · regla de negocio** | ¿Puede hacer **esto** con **estos datos**? | lógica del caso de uso del microservicio | un token con `scope: users.profile.read` pasó la capa 1, pero un intento de **escritura** se rechaza en el caso de uso, no en la anotación |

Ambas viven **enteras** en el microservicio destino. Es válido tanto para roles de persona como para el rol `MS`.

**Por qué `@PreAuthorize` y no `@RolesAllowed`:** soporta SpEL, así que permite combinar rol + condición de negocio en la misma anotación (`hasRole('PROFESSOR') and #cursoId == principal.cursoId`) sin write una clase aparte, y se integra nativo con el `Authentication` que cada micro arma a partir de los headers propagados. `@RolesAllowed` (JSR-250) es más portable pero se queda corto para cualquier cosa que no sea "tiene este rol sí/no".

### 11.3 La única excepción, y por qué no lo es

La validación de `sid` contra Redis (§9.1) **no** es autorización: es **autenticación** (*"¿este token representa una sesión vigente?"*), no una pregunta de rol (*"¿puede este rol tocar esta ruta?"*). El Gateway sigue sin tomar ninguna decisión de negocio: solo extendió, con este único caso, qué significa "un token válido".

### 11.4 Comunicación micro→micro: dos casos, un solo camino

| Caso | Cuándo | Qué token viaja | Qué ve el destino |
|---|---|---|---|
| **a · con persona detrás** | Un micro atiende el pedido de un usuario y necesita datos de otro micro | **reenvía el mismo JWT de la persona** — no pide uno nuevo, no lo cambia por uno de servicio | el rol real de la persona (`ADMIN`/`PROFESSOR`/`STUDENT`) |
| **b · sin persona detrás** | Un job, tarea programada o proceso que actúa como sistema | pide su propio token con `clientId`+`clientSecret` (§11.5) | rol `MS` + `aud` + `scope` |

**Por qué se descarta "nunca JWT de persona entre micros":** existe una postura de industria que dice que un micro nunca debería recibir el JWT de una persona como credencial hacia otro micro (siempre token de servicio + header de contexto delegado). **Para este proyecto se descarta**: reenviar el JWT tal cual es más simple, evita inventar un mecanismo de delegación paralelo, y el destino igual necesita el rol real de la persona para autorizar — tenerlo en el propio token con firma verificada es más directo que reconstruirlo desde un header adicional.

Para el Gateway, ambos casos son **el mismo camino**: valida firma (+ `sid` si es `type: user`), rutea y propaga. La diferencia la ve el destino, no el Gateway.

### 11.5 Cómo se obtiene un token de servicio

```http
POST /api/users/public/auth/token HTTP/1.1
Host: gateway:8080
Content-Type: application/json

{
  "clientId":     "cursos-service",
  "clientSecret": "…",                  // de una variable de entorno, NUNCA del repo
  "grantType":    "client_credentials",
  "scope":        "users.profile.read",
  "audience":     "users-service"       // DEC-17 — campo nuevo, obligatorio
}
```

**No lleva `Authorization`**: es el request que consigue el token. Y el request que **usa** el token no lleva credenciales. Son excluyentes.

Esta ruta **ya no es una excepción documentada**: con el prefijo `public` explícito (§5.1), es simplemente pública — no hace falta un token para pedir un token.

> ⚠️ El campo `scope` en el body aparece en el manifiesto del Gateway §05.3 y **no** aparece en el mismo request de `jwt-jwks-redis` §7.3c. Ver §14 / INC-09. Para el Gateway es indiferente (es una ruta pública que solo se rutea), pero el generador del cliente de `users-service` tiene que resolverlo.

> 📢 **`DEC-17` · El campo `audience` no está en ningún manifiesto: es nuevo.** Sin él, `users-service` no tiene de dónde sacar el `aud` que el `ServiceAudienceFilter` (`DEC-04`, §9.7) exige — todo token de servicio sería rechazado con `403`. `users-service` lo valida contra el prefijo del `scope` y devuelve `400` si no coincide (`SPEC-users-service.md` §10.2). **Es un cambio de contrato para los equipos consumidores**: entra por el runbook de §6.5.

### 11.6 Catálogo de scopes (referencia — el Gateway no lo valida)

| Scope | Habilita | Quién lo pide |
|---|---|---|
| `users.profile.read` | `GET /api/users/profile/{id}` | Cursos, Desafíos |
| `users.padron.notify` | evento de ida-vuelta de validación de padrón | Cursos |
| `mailing.debug.read` | lectura de logs/estado de envíos | equipo propio, debug interno |

Catálogo **cerrado**: `POST /api/users/public/auth/token` nunca emite un scope fuera de este set. El Gateway lo propaga sin interpretarlo.

---

## 12. Resiliencia, observabilidad y endpoints propios

### 12.1 Las cinco capas, en orden

> **El orden importa: cada capa cubre una falla distinta.**
> `rate limit → bulkhead → timeout → retry controlado → circuit breaker → fallback`
> El breaker solo no protege memoria ni conexiones; el rate limit solo no evita que un destino lento agote el pool. **Se usan todas juntas, no una en reemplazo de otra.**

| Capa | Implementación | Detalle |
|---|---|---|
| **Rate limit** | `RateLimitFilter` (§9.9) + `RateLimitKeyResolver` | 429 + `Retry-After` · `type: too-many-attempts` (**DEC-24**) |
| **Bulkhead** | Resilience4j | limita concurrencia **por destino**, para que un destino lento no agote el pool del Gateway |
| **Timeout** | Resilience4j `TimeLimiter` | `TODO-03` |
| **Retry** | Resilience4j | **solo en operaciones idempotentes** (GET, o con idempotency key), con **backoff + jitter** |
| **Circuit breaker** | Resilience4j, **un breaker por `serviceId` destino** | abre ante fallas repetidas y responde **503 rápido** vía `FallbackController`, en vez de colgar al cliente |
| **Health checks** | Actuator liveness/readiness | Eureka usa **readiness** para el balanceo; el orquestador usa **liveness** para reiniciar |

### 12.2 Endpoints propios del Gateway

| Endpoint | Para qué | Exposición |
|---|---|---|
| `GET /actuator/health/liveness` | ¿el proceso está vivo? | interno · red/puerto de management |
| `GET /actuator/health/readiness` | ¿puede recibir tráfico? Lo usa Eureka | interno · management |
| `GET /actuator/prometheus` | métricas: latencia, throughput, 4xx/5xx, breakers | interno |
| `forward:/fallback/**` | destino del circuit breaker; responde `ProblemDetail` | interno |

**Actuator va en un puerto o red de management, no expuesto a Internet** (`management.server.port`). `/actuator/gateway`, si se habilita para debug, queda **read-only e interno**.

Todo lo demás **no es un endpoint propio**: es tráfico reenviado siguiendo el estándar `/api/{nombre}/**`.

### 12.3 Regla general del proyecto

> No se fuerza un patrón de diseño ni de arquitectura por moda. Donde resuelve algo real del problema (Repository, Strategy, Chain of Responsibility vía `GlobalFilter`, Circuit Breaker) se aplica; si no suma sobre la solución simple, no se mete por prolijidad.

---

## 13. Convenciones de código y errores

Aplican desde el primer commit (Gateway §11).

### 13.1 Patrón Repository explícito

Interfaces (contratos) en un paquete, implementaciones en `impl/`. **No mezclar contrato e implementación en la misma clase.**

```
repository/SessionRepository.java            ← interfaz
repository/impl/RedisSessionRepository.java  ← implementación
ratelimit/RateLimitKeyResolver.java          ← interfaz
ratelimit/impl/PrincipalRateLimitKeyResolver.java
```

### 13.2 Manejo de errores centralizado

**Un handler global**, no `try/catch` disperso. Siempre el **mismo formato `ProblemDetail`** (RFC 9457) — el mismo que usan los 401/403/429/503 que emite el propio Gateway.

```jsonc
{
  "type":     "https://tpi.utn.frc/errors/session-superseded",
  "title":    "Sesión superada",
  "status":   401,
  "detail":   "La sesión fue invalidada por un login más reciente.",
  "instance": "/api/users/me",
  "requestId": "…",     // X-Request-Id, para poder cruzar con los logs
  "traceId":   "…"
}
```

Cubre: `401` (token inválido / expirado / `sid` superado / falta autenticación), `404` (servicio fuera de la allowlist), `429` (rate limit, con `Retry-After`), `503` (breaker abierto → fallback; Redis no responde → §9.1.1 / **DEC-01**), `403` (`aud` de token de servicio que no corresponde al destino → §9.7 / **DEC-04**).

**El `ProblemDetail` nunca revela** qué servicios existen, ni por qué exactamente falló la validación de firma.

### 13.3 Logs limpios y trazables

Mensajes **estructurados y consistentes entre filtros**, siempre con el trace id visible (§10.3). El objetivo es que rastrear un request de punta a punta sea rápido, no reconstruir la secuencia a mano. **Nunca bodies, nunca tokens, nunca secrets.**

---

## 14. ⚠️ Inconsistencias detectadas

> Todas las que siguen son **inconsistencias reales entre los documentos fuente**. Este documento no las "arregla" en los manifiestos: los manifiestos siguen contradiciéndose y hay que corregirlos.
> Algunas fueron **cerradas por decisión del equipo** (§14.0) porque bloqueaban el código; el resto sigue abierta.
> Prioridad: 🔴 bloquea código correcto · 🟡 afecta el contrato con otro equipo · 🔵 documental.
> Estado: ✅ cerrada por `DEC-xx` · ⏳ abierta.

---

### 14.0 Decisiones tomadas por el equipo

**Ninguna de estas sale de un manifiesto.** Se tomaron para cerrar huecos que bloqueaban la implementación. Cada una debe reflejarse de vuelta en el manifiesto correspondiente, y **cinco de ellas obligan a un cambio en `users-service`**.

| ID | Decisión | Cierra | Cambio requerido en otro componente |
|---|---|---|---|
| **DEC-01** | **Fail-closed distinguiendo causa** en la validación de `sid`: Redis no responde → **503 + `Retry-After`**; key ausente → **401 sesión cerrada**; key distinta → **401 sesión superada**. Nunca fail-open. Métrica separada por rama. (§9.1.1) | INC-03 | — (interno del Gateway) |
| **DEC-02** | **El logout borra `session:{userId}`.** Efecto: el logout corta el access token al instante. (§9.1.1) | INC-02 | 🔧 **users-service**: agregar el borrado de la key al flujo de logout, que hoy `flujos` §05 no incluye |
| **DEC-03** | **El Gateway reenvía el `Authorization` original** al destino (comportamiento por defecto de SCG; no agregar `RemoveRequestHeader`). Habilita zero-trust interno y resuelve `on_behalf_of` sin header nuevo. (§10.4) | INC-05 | 📢 **todos los micros**: pueden validar el token por su cuenta contra el JWKS |
| **DEC-04** | **`ServiceAudienceFilter`, paso 7 del pipeline** (post-routing): compara `aud` contra el `serviceId` destino ya resuelto; **403** si no coincide. El pipeline pasa de 7 a 8 pasos, y a 9 con `DEC-23`. (§9.7) | INC-06 | 🔧 **manifiesto del Gateway §03**: documentar el paso nuevo |
| **DEC-05** | **Formato de headers: coma sin espacio**, `MS` incluido en `X-Service-Scopes` (`MS,users.profile.read`). (§10.1b) | INC-08 | 🔧 **users-service** y todo micro destino: parsear con `split(",")`, mapear `MS` → `ROLE_MS` |
| **DEC-06** | **Allowlist inicial: solo `users-service`.** Todo servicio nuevo entra por el runbook de §6.5. `mailing-service` no entra hasta cerrar INC-12. (§6.5) | INC-12 (parcial) | 📢 **otros equipos**: checklist de §6.5.1 |
| **DEC-07** | **`iss` = `"users-service"`** (nombre lógico, no URL). Se valida con `JwtClaimValidator<String>`, no con `issuer-uri`. (§9.1) | INC-11 | 🔧 **users-service**: **emitir** el claim `iss` en ambos tipos de token — hoy no aparece en el contrato de §04. Sin esto, **todos** los tokens fallan con 401 |
| **DEC-08** | `users-service` **no valida el JWT**: su `Authentication` sale solo de los headers `X-*` | — | 📢 confirma que el set de headers de §06 es el contrato real, no una comodidad. Ver `SPEC-users-service.md` §7 |
| **DEC-10** | `on_behalf_of` se queda en los **logs del Gateway** | limita INC-05 | 📢 el `LoggingFilter` (§9.3) es el **único** registro de ese claim |
| **DEC-16** | El reset de password son **dos** endpoints públicos: `…/reset` y `…/reset/confirm` | INC-22 (users) | 🔧 **Gateway**: nada que configurar (ambos caen bajo `/public/**`), pero el catálogo de §5.2 lo lista |
| **DEC-17** | El request de `client_credentials` suma un campo **`audience`** obligatorio | INC-24 (users) | 🔧 **users-service**: emitir el `aud` desde ese campo. 📢 **todos los consumidores**: agregar el campo o no obtienen token (§11.5) |
| **DEC-18** | Claves RS256 desde **PEM montados como secret**, `kid` activo por env var; nunca generadas al arrancar | TODO-04 (users) | 📢 **Gateway**: es lo que hace válida la caché del JWKS por `kid` (§9.2). Con claves generadas al arrancar, el re-fetch reactivo pasaría a ser permanente y habría `401` alternantes entre instancias |
| ~~**DEC-19**~~ | ~~La validación de `iss` se activa por configuración~~ | — | 🔴 **RETIRADA por `DEC-44`** — sobreingeniería (§9.1.0) |
| ~~**DEC-43**~~ | ~~Switch de tres estados `off\|warn\|enforce`~~ | — | 🔴 **RETIRADA por `DEC-44`** — sobreingeniería (§9.1.0) |
| **DEC-44** | **Los claims nuevos se validan siempre, sin flag.** La red de seguridad es `TokenContractTest` + un `401` que loguea qué claim faltó | reemplaza DEC-19 y DEC-43 | 🔧 **users-service**: emitir los cuatro claims desde el primer commit, y `TokenContractTest` que lo garantice |
| **DEC-20** | La base relacional de `users-service` es **MySQL 8.4 LTS**, no PostgreSQL | — | ninguno para el Gateway (no tiene base). Se registra acá solo para que las dos specs digan lo mismo |
| **DEC-22** | El refresh **no** genera `sid` nuevo ni escribe `session:{userId}` | INC-01 | ninguno para el Gateway: sigue solo comparando. 🔧 **users-service**: `TokenService.rotar()` y el chequeo de sesión en el refresh |
| **DEC-23** | **`AccountStateGuard`, paso 6 del pipeline**: bloquea a una persona con la cuenta no habilitada fuera de `/api/users/**`. Se apaga por config hasta que existan los claims (§9.6) | INC-18 | 🔧 **users-service**: emitir `est`/`pwd`/`onb` y borrar `session:{userId}` en `deactivate()`. 🔧 **manifiesto de flujos §11**: el carril dibujado es imposible |
| **DEC-24** | Rate limit repartido: Gateway **por IP** sobre `/api/users/public/auth/**`, `auth/` **por email**. Mismo `429` (§9.9.1) | INC-15 | 📢 **users-service**: mantener su limitador por email; no es redundante |
| **DEC-25** | Caché local de `session:{userId}` con **TTL 3 s** (§9.1.2) | INC-04 (parcial) | 🔧 **manifiesto de flujos §05**: su justificación quedó invalidada por v5 |
| **DEC-27** | **Ruta estática para `GET /.well-known/jwks.json`** (§6.4, §7). Sin ella la ruta pasaba todo el pipeline y moría en un `404` del Gateway | mecanismo faltante de INC-07 | 🔧 **manifiesto del Gateway §05**: la excepción de ruta necesita ruta declarada, no solo `permitAll` |
| **DEC-28** | **Puertos fijados:** Gateway `8080` (management `8081`), `users-service` `8082` (management `8083`) | TODO-02 | 🔧 **users-service**: `server.port: 8082`, `management.server.port: 8083` |
| **DEC-35** | Spring Cloud **2025.1.3 "Oakwood"** con Boot **4.1.1** — verificado contra la matriz oficial | TODO-01 | 🔧 **users-service**: mismo pin (paridad `DEC-15`) |
| **DEC-36** | `GET /api/users/profile/{id}` acepta **token de persona además de `MS`** | INC-10 | 🔧 **users-service**: el `@PreAuthorize` de esa ruta. Ninguno para el Gateway |
| **DEC-37** | Tópicos `<producer>.<asunto>.<version>` | TODO-12 | ninguno para el Gateway (no toca Kafka) |
| **DEC-38** | Política de contraseñas, sesiones y PII | TODO-15 | ninguno para el Gateway |
| **DEC-39** | **Ante discrepancia, prevalece la spec sobre el anexo** | INC-13, 14, 16, 25 | regla de lectura, no de código |
| **DEC-40** | `Dockerfile` + `docker-compose.yml` (§16) | TODO-02 / TODO-03 | 🔧 **ambos**: el `Dockerfile` es el mismo patrón en los dos repos |
| **DEC-41** | `notifications-service` es **solo consumidor de Kafka**: sin ruta, sin allowlist, sin scope | INC-12 | 🔧 **users-service**: eliminar `mailing.debug.read` del catálogo §04.0b |
| **DEC-42** | Valores estándar de rate limit y resiliencia, a recalibrar tras la primera prueba de carga | TODO-03, TODO-09 | 🔧 **users-service**: 5 fallos / 15 min por email |
| **DEC-46** | **El contrato HTTP y el código están en inglés; lo que es de otro equipo, no** | — | 🔧 **ambos**: mismos `type` de error, mismos valores de rol. El Gateway y `users-service` comparten un solo espacio de nombres y tiene que leerse igual en los dos |

> ⚠️ **Los cambios marcados 🔧 en `users-service` hay que coordinarlos antes de integrar.** El más visible es **DEC-07**: si el Gateway valida `iss` y `users-service` no lo emite, todo devuelve 401. **`DEC-44` lo resuelve donde corresponde** — los cuatro claims son parte del contrato de token, `TokenContractTest` no deja emitir uno sin ellos, y si igual pasa el `401` dice cuál falta. Sin flags. Ver §9.1.0.

---

### INC-01 🟡 ✅ · `sid` nuevo en el refresh: sí o no · **cerrada por `DEC-22`**

| Documento | Dice |
|---|---|
| `manifiesto-users-service` §04.1 | *"Al emitir tokens (login exitoso tras 2FA, **o refresh**), `TokenService` genera un `sid` nuevo"* |
| `jwt-jwks-redis` §06.5, tabla paso 1 | *"Login exitoso (post-2FA) **o refresh** → `auth/` genera un `sid` nuevo"* |
| `jwt-jwks-redis` §08, runbook "Renovar la sesión" | *"(v5) **El `sid` NO cambia en un refresh** — es la misma sesión continuando, no un login nuevo. Solo un login post-2FA genera `sid` nuevo."* |

**El anexo se contradice consigo mismo**, y su §08 contradice al manifiesto de `users-service`.
**Impacto en el Gateway:** ninguno en el código (el Gateway solo compara claim vs Redis). **Impacto en el DoD:** la prueba de integración de sesión única (§15) da resultados distintos según la versión. Lo tiene que cerrar el equipo de `users-service`.

---

### INC-02 🔴 ✅ · Qué hace el logout con `session:{userId}`

| Documento | Dice |
|---|---|
| `manifiesto-flujos` §05 | El logout **solo** hace blacklist del `jti` del refresh. *"El access token, al ser stateless y de vida corta, simplemente se deja expirar."* |
| `manifiesto-users-service` §04.1 | No menciona el logout entre los eventos que tocan `session:{userId}`. |
| `jwt-jwks-redis` §08, runbook "Cerrar sesión" | *"(v5) `auth/` también **borra (o pisa con un value inválido)** `session:{userId}`. […] el logout ahora sí corta el access al instante."* |

Tres documentos, tres versiones. Peor: *"borra **o** pisa"* es ambiguo **dentro del mismo documento**.
**Impacto directo en el Gateway:** define qué debe hacer cuando la key **no existe**. Si el logout borra la key, "key ausente" = sesión cerrada → **401**. Si el logout no la toca, "key ausente" = Redis reiniciado → el 401 masivo es un efecto de infraestructura, no de negocio. Es exactamente el mismo síntoma con dos causas opuestas.

> ✅ **Cerrada por DEC-02:** el logout **borra** la key. "Key ausente" = sesión cerrada → **401**. `users-service` tiene que agregar ese borrado a su flujo de logout, que hoy no lo tiene.

---

### INC-03 🔴 ✅ · Fail-mode del Gateway con Redis caído · **dato faltante**

Ningún documento define el comportamiento del Gateway ante un timeout o error de conexión a Redis en el paso 1 del pipeline. `users-service` §04.1 y `jwt-jwks-redis` §06.5 describen el **trade-off** (*"si Redis no responde, el Gateway no puede validar ningún access token de persona"*) pero **nunca lo fijan como spec**: no dicen si responde 401, 503, ni si hay `Retry-After`, ni si hay degradación temporal.

Es la decisión con más impacto operativo del componente (la autenticación de toda la plataforma queda atada a la disponibilidad de Redis) y **no estaba escrita en ninguna parte**.

> ✅ **Cerrada por DEC-01:** fail-closed distinguiendo causa — **503 + `Retry-After`** si Redis no responde, **401** si la key falta o no coincide. Nunca fail-open. Tabla completa en §9.1.1.

---

### INC-04 🟡 ✅ · Contradicción de fondo: "Redis en el camino crítico" · **mitigada por `DEC-25`**

`manifiesto-flujos` §05 justifica no revocar el access token así:

> *"Revocar el access obligaría al Gateway a chequear una blacklist en cada request → adiós stateless, y **Redis en el camino crítico de todo**. […] clave para aguantar los 120 requests concurrentes del examen **sin martillar Redis**."*

Pero la revisión v5 hace **exactamente eso**: una lectura a Redis por request de persona. El argumento quedó en el documento sin actualizarse tras el parche v5, y el propio `jwt-jwks-redis` lo admite (*"Dejamos el razonamiento viejo en el resto del documento tal cual estaba"*).

No cambia qué implementar (v5 manda), pero **invalida la justificación** que un lector podría tomar como vigente — incluida la afirmación de que el diseño evita "martillar Redis" bajo el pico de examen, que ya no es cierta.

**`DEC-25` · Mitigada, con una precisión importante:** el riesgo real **no es de carga sino de disponibilidad**. Los números están en §9.1.2 — 120 concurrentes son ~1.200 GET/s contra un Redis que hace ~100.000 ops/s. Lo que sí cambió con v5 es que **Redis caído = plataforma caída**. La caché local de 3 s acota esa ventana. La mitad documental (actualizar `manifiesto-flujos` §05) sigue pendiente: 🔧.

---

### INC-05 🟡 ✅ · `on_behalf_of` no tiene canal de propagación · **dato faltante**

- `manifiesto-api-gateway` §05.2: `on_behalf_of` *"queda registrado para auditoría"*.
- `manifiesto-api-gateway` §06: la tabla de headers inyectados **no lo incluye**, y el filtro *"limpia primero cualquier header entrante con estos nombres y después inyecta el set validado"*.
- Ningún documento dice si el `Authorization` original se reenvía al destino (§10.4).

Resultado: el metadato de trazabilidad **no tenía forma documentada de llegar al microservicio destino**.

> ✅ **Cerrada por DEC-03**, con una salvedad de `DEC-10`: el Gateway **reenvía el `Authorization` original**, así que el claim llega al destino — pero `users-service` decidió no parsear el JWT (`DEC-08`), así que en la práctica no lo lee. Para ese destino, `on_behalf_of` vive solo en los logs del Gateway. **No se creó** un header `X-On-Behalf-Of`. El manifiesto §06 sigue sin mencionar el reenvío del `Authorization` — hay que agregarlo.

---

### INC-06 🔴 ✅ · Validación de `aud`: exigida, sin mecanismo

- `manifiesto-api-gateway` §05.2 y §12 (DoD): *"El Gateway rechaza un token de servicio cuyo `aud` no coincide con **el destino real**"* — es criterio de aceptación.
- `manifiesto-users-service` §04.1: *"El Gateway rechaza un token de servicio cuyo `aud` no coincide con `users-service`"* — sugiere un value **fijo**, no derivado del destino.
- `jwt-jwks-redis` §7.2 (paso 4) y §7.3c: el Gateway *"verifica la firma"* y rutea. **No menciona `aud` en absoluto.**

Además, ningún documento explica **cómo** el Gateway conoce "el destino real" en el momento de validar: en WebFlux, la cadena de Security corre antes de que Gateway resuelva el `serviceId`.

> ✅ **Cerrada por DEC-04:** `ServiceAudienceFilter`, el **paso 7 del pipeline** (post-routing), que compara `aud` contra el `serviceId` resuelto y responde **403**. Contrato en §9.7.
> **Queda pendiente en los manifiestos:** el §03 del Gateway sigue documentando 7 filtros, y `users-service` §04.1 sigue sugiriendo un `aud` fijo en vez de uno por destino.

---

### INC-07 🟡 ✅ · ¿El JWKS pasa o no pasa por el Gateway? · **cerrada por `DEC-27`**

| Documento | Dice |
|---|---|
| `manifiesto-api-gateway` §01 | `app.api.public-path` cubre *"Login, registro, refresh, **JWKS well-known**, token de servicio"* — implica ruta pública ruteada. |
| `manifiesto-api-gateway` §05.3 | *"Solo queda una excepción real: `/.well-known/jwks.json`"* — excepción de **naming**, no de ruteo. |
| `jwt-jwks-redis` §03.2 | *"**Ojo: este request NO pasa por el Gateway.** […] Los micros lo consultan directo a `users-service` por la red interna de Docker, usando el nombre del servicio como host."* |

Contradicción directa. **Prioricé el manifiesto del Gateway** (ruta pública, `permitAll`, en `well-known-paths`). Nota técnica: el argumento del anexo es válido para el propio Gateway (no puede pasarse por sí mismo para conseguir la clave con la que valida) — por eso `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` en §7 apunta **directo a `users-service`**, no al Gateway. Ambas cosas pueden convivir; hay que confirmar cuál es el contrato para **los otros 11 micros**.

---

### INC-08 🟡 ✅ · Formato de `X-User-Roles` y `X-Service-Scopes` · **dato faltante**

`manifiesto-api-gateway` §06 muestra el value de `X-User-Roles` como `STUDENT, PROFESSOR, ADMIN` (sin decir si es una lista de valores posibles o un ejemplo de value real) y el de `X-Service-Scopes` como `"MS + el scope del token"` — prosa, no formato. **No había separador definido, ni si `X-Service-Scopes` mezcla rol y scope en un mismo string.** Es un contrato de parsing con `users-service`.

> ✅ **Cerrada por DEC-05:** coma sin espacio, `MS` incluido en `X-Service-Scopes` (`MS,users.profile.read`). Reglas completas de serialización y de mapeo a `GrantedAuthority` en §10.1b.

---

### INC-09 🔵 ✅ · `scope` en el request de client credentials · **cerrada por `DEC-17`**

`manifiesto-api-gateway` §05.3 muestra el body con `"scope": "users.profile.read"`; `jwt-jwks-redis` §7.3c muestra **el mismo request sin ese campo**. `users-service` §04.0b dice que cada `service_client` tiene scopes asociados en la base — lo que sugiere que el scope podría no venir del request. No afecta al Gateway (ruta pública que solo se rutea), sí al contrato de `users-service`.

---

### INC-10 🟡 ✅ · `GET /api/users/profile/{id}` con token de persona · **cerrada por `DEC-36`**

- `manifiesto-users-service` §12.1: la ruta es *"privado · **rol MS + scope**"*, y §07 refuerza: *"`GET /api/users/profile/{id}` exige rol MS, **nunca un rol de persona**"*.
- `jwt-jwks-redis` §7.4, "Flujo real 3": *"Cursos […] **reenvía el mismo token del profesor** […] y manda el request al Gateway […] `users-service` devuelve los datos de perfil"* — con token de persona, contra esa misma ruta.

El caso "a" del Gateway §02 (reenviar el JWT de persona) es válido en general, pero el ejemplo concreto del anexo choca de frente con la regla de `users-service`. Sin impacto en el Gateway (no autoriza), pero es un flujo end-to-end que va a fallar con 403 si alguien lo implementa como está escrito en el anexo.

---

### INC-11 🔴 ✅ · `iss` exigido, value no definido · **dato faltante**

`manifiesto-api-gateway` §03 p.1 lista `iss` entre lo que Security valida. **Ningún documento fijaba el value del claim `issuer`** — y, peor, `iss` **no aparece como claim** en el contrato de token de §05 (ni el de persona ni el de servicio), ni en `users-service` §04, ni en el anexo.

> ✅ **Cerrada por DEC-07:** `iss = "users-service"`.
> ⚠️ **Riesgo de secuencia:** `users-service` hoy **no emite** ese claim. Si el Gateway activa la validación primero, **todo el sistema devuelve 401**. Orden obligatorio: (1) `users-service` agrega `iss` a ambos tokens y lo documenta en su §04; (2) recién ahí el Gateway lo valida.

---

### INC-12 🟡 ✅ · La allowlist del ejemplo incluye un servicio fuera de alcance · **cerrada por `DEC-41`**

`manifiesto-api-gateway` §04:

```yaml
include-expression: "'USERS-SERVICE,MAILING-SERVICE,...'.contains(serviceId)"
```

Pero `manifiesto-users-service` §01.1 dice que **Mailing salió del alcance del equipo** y fue reemplazado por `notifications-service`, **externo**, que *"no nos llama por HTTP, solo consume eventos de Kafka"* — es decir, no debería necesitar ruta en el Gateway. A la vez, `users-service` §04.0b conserva el scope `mailing.debug.read` para *"el único endpoint HTTP restante de mailing"*.

**Sigue sin estar claro si `mailing-service` existe como servicio registrado en Eureka.**

> ✅ **Cerrada por `DEC-41`: `notifications-service` es un consumidor de Kafka, no un servicio HTTP nuestro.**
>
> Confirmado por el equipo: el servicio de notificaciones **va a existir**, lo hace otro equipo, y **no sabemos ni nos importa cómo está construido por dentro**. Lo único que compartimos con él es el **contrato de la cola de eventos** (`DEC-12`), por donde le avisamos que tiene que salir un mail.
>
> Consecuencias, todas en la dirección de sacar cosas:
>
> | | Resolución |
> |---|---|
> | Entrada en la allowlist del Gateway | **No.** No le pegamos por HTTP. `DEC-06` queda como definitiva, no provisoria |
> | Ruta `/api/mailing/**` o `/api/notifications/**` | **No existe** |
> | Scope `mailing.debug.read` | 🔧 **se elimina** del catálogo de `users-service` §04.0b. Apuntaba a "el único endpoint HTTP restante de mailing", que no es nuestro ni lo consumimos |
> | `mailing-service` en Eureka | Irrelevante para el Gateway. Si el otro equipo lo registra, no entra a la allowlist hasta que alguien lo pida por el runbook de §6.5 |
>
> **Efecto neto:** el catálogo de scopes emitibles queda en **uno solo**, `users.profile.read` (`DEC-26`), y la superficie HTTP del Gateway sigue siendo un solo servicio. Menos que mantener.

---

### INC-13 🔵 ✅ · Terminología "rol MS" en el anexo · **cerrada por `DEC-39`**

`jwt-jwks-redis` usa el rol `MS` de forma **inconsistente** respecto de los manifiestos:

- §7.3 (caso B) y §08 (runbook "Un micro llama a otro") describen el token de servicio solo como `type: service`, **sin nombrar el rol `MS`** ni los claims `aud`/`scope`/`on_behalf_of` que la revisión v4 hizo obligatorios.
- §08 runbook, paso 6: *"B ve un token válido que identifica al servicio A, y **decide qué puede hacer (capa 2)**"* — saltea la capa 1 (`hasRole('MS')`), que en `manifiesto-api-gateway` §07 y `users-service` §07 es explícitamente parte del contrato.
- §7.3c (comentarios del request) sí lo nombra correctamente.

**Prioricé `manifiesto-api-gateway` §05.2/§07:** el token de servicio lleva `roles: ["MS"]`, `aud`, `scope` y `on_behalf_of`, y el destino valida **capa 1 (`hasRole('MS')`) y capa 2 (`scope` + regla de negocio)**.

---

### INC-14 🔵 ✅ · El anexo atribuye autorización al Gateway · **cerrada por `DEC-39`**

- `jwt-jwks-redis` §7.2, diagrama: la caja del API Gateway dice *"1· valida firma del token / **2· chequea permiso de ruta**"*.
- §7.5, analogía de cierre: *"Si su credencial está vencida **o no lo habilita para esa oficina**, recepción lo frena ahí mismo."*

Contradice R3 y el propio §7.2 paso 5 del mismo documento (*"La autorización por rol la resuelve `users-service`, no el Gateway"*). Es una imprecisión de prosa, pero un agente de generación de código podría tomarla como spec. **Prioricé el manifiesto: el Gateway no chequea permisos de ruta por rol.**

---

### INC-15 🟡 ✅ · Rate limit de login: dos dueños · **cerrada por `DEC-24`**

- `manifiesto-api-gateway` §03 p.7: `RateLimitFilter`, *"solo en rutas marcadas como caras"*.
- `manifiesto-users-service` §05 y §10: `auth/` guarda *"rate limit de login"* en Redis, y `TokenStore` expone `rateLimit(key)`.
- `manifiesto-flujos` §02: el paso *"rate-limit login (¿demasiados intentos?)"* está **en el carril de `auth/`**, no en el del Gateway.

**No estaba definido** si `/api/users/public/auth/login` es una "ruta cara" del Gateway, ni cómo interactúan los dos limitadores si ambos aplican.

**`DEC-24` · Resuelto: no era un conflicto.** Limitan cosas distintas con claves distintas — Gateway por **IP** (inundación), `auth/` por **email** (fuerza bruta) — así que no pueden dispararse por la misma condición y el que salta primero responde. Sí es una ruta cara: `/api/users/public/auth/**` entra en `expensive-routes`. Tabla completa y el gotcha del `X-Forwarded-For` en §9.9.1. Los umbrales siguen en `TODO-03`; lo que bloqueaba era el reparto.

---

### INC-16 🔵 ✅ · Numeración de los manifiestos, cruzada · **cerrada por `DEC-39`**

`manifiesto-api-gateway` se rotula *"Manifiesto de componente · **1 de 3**"* y `manifiesto-users-service` también *"**1 de 3**"*; pero las referencias cruzadas internas llaman *"manifiesto 2"* al Gateway y *"manifiesto 1"* a `users-service` (y a veces *"manifiesto 1"* al propio Gateway, ej. §08: *"queda en la misma categoría que el docker-compose (manifiesto 1, pendiente)"*). Un agente que siga las referencias por número va a abrir el documento equivocado. Sin impacto en el código.

---

### INC-17 🟡 ✅ · Discrepancias del PDF de propuesta de arquitectura general · **cerrada por el equipo**

`TUP_PIV_BE_PROPUESTA_ARQ.pdf` es un documento de plataforma, anterior y de menor autoridad. Donde contradice al manifiesto, **prioricé el manifiesto** — pero las reporto igual, como pide el brief:

| # | PDF dice | Manifiestos dicen |
|---|---|---|
| a | Tema 01, "Pedido para empezar": **"Roles: ADMIN, responsable, profesor, alumno"** — **cuatro** roles, incluido **`responsable`** | Tres roles de persona: `ADMIN` / `PROFESSOR` / `STUDENT` (users-service §03, Gateway §06) + el rol `MS`, exclusivo de servicios. **`responsable` no existe en ningún manifiesto.** Si ese rol es real, `X-User-Roles` tiene un value posible más y el enum `Role` de `users-service` está incompleto. |
| b | Tema 01, columna **"Podría ser"**: *"Doble factor"* — es decir, un extra opcional | 2FA **obligatorio en cada login**, sin dispositivos de confianza (users-service §06, flujos §02, RF-NFR-02). Es núcleo, no extra. |
| c | Tema 01, columna **"Para más adelante"**: *"Revocación de sesión"* | La sesión única con invalidación inmediata es **v5 y núcleo**, y es la única razón por la que el Gateway toca Redis. |
| d | §1.2: *"el gateway […] Ante cada solicitud **consulta el registro** y obtiene una instancia viva"* | El Gateway resuelve contra la **caché local de Eureka** (`fetch-registry=true`), no consultando el registry en cada request (Gateway §04, §10). El PDF describe mal el mecanismo. |
| e | §4.7: *"**Dónde se resuelve la autorización es una decisión de diseño que cada equipo debe justificar**"* — lo deja abierto | Cerrado y lockeado: **entera en el microservicio destino** (R3). Coincide en espíritu (*"Validar no es autorizar"*), pero el manifiesto ya no lo deja abierto. |
| f | El PDF aclara en su cierre que fue *"elaborado con asistencia de herramientas de IA"* y *"puede contener imprecisiones"*, y que **no debe interpretarse como especificación cerrada** | — (contexto que respalda tratarlo como fuente de menor autoridad) |

---

### Consolidado de `TODO`s

> ⚠️ **Los `TODO-xx` son locales a este documento**, a diferencia de los `INC-xx` y los `DEC-xx`, que sí comparten numeración entre las dos specs. `TODO-04` acá **no** es `TODO-04` en la otra spec. Citá siempre el archivo junto al ID.

| ID | Qué falta | Bloquea |
|---|---|---|
| ~~TODO-01~~ | ~~Pin del release train de Spring Cloud~~ | ✅ cerrado por **DEC-35**: `2025.1.3`, verificado contra la matriz oficial |
| ~~TODO-02~~ | ~~`Dockerfile` + `docker-compose.yml`~~ | ✅ cerrado por **DEC-40** (§16) |
| ~~TODO-03~~ | ~~Umbrales de rate limit, rutas caras, pool de Redis, timeouts~~ | ✅ cerrado por **DEC-42** (§7): valores estándar puestos, a recalibrar tras la prueba de carga |
| ~~TODO-04~~ | ~~Mecanismo de validación de `aud` por destino~~ | ✅ cerrado por **DEC-04** |
| ~~TODO-05~~ | ~~Valor del claim `iss`~~ | ✅ cerrado por **DEC-07** |
| ~~TODO-06~~ | ~~Fail-mode con Redis caído / key ausente~~ | ✅ cerrado por **DEC-01 + DEC-02** |
| ~~TODO-07~~ | ~~Formato/separador de `X-User-Roles` y `X-Service-Scopes`~~ | ✅ cerrado por **DEC-05** |
| ~~TODO-08~~ | ~~¿Se reenvía el `Authorization` original al destino?~~ | ✅ cerrado por **DEC-03** |
| ~~TODO-09~~ | ~~Reparto del rate limit de login con `auth/`~~ | ✅ cerrado por **DEC-24** (§9.9.1) |

| ~~TODO-10~~ | ~~¿`mailing-service` existe como servicio HTTP?~~ | ✅ cerrado por **DEC-41**: irrelevante, es solo consumidor de Kafka |
| ~~TODO-11~~ | ~~Confirmar el package `…p4.apigateway`~~ | ✅ confirmado por el equipo (**DEC-15**) |

### Cambios que hay que pedirle a `users-service` (derivados de §14.0)

| Cambio | Por | Riesgo si no se hace |
|---|---|---|
| **Emitir el claim `iss: "users-service"`** en ambos tipos de token | DEC-07 | 🔴 **todo el sistema devuelve 401.** Hacerlo **antes** de que el Gateway active la validación |
| **Borrar `session:{userId}` en el logout** | DEC-02 | el logout no corta el access token; queda la ventana de ~10 min que el manifiesto quería cerrar |
| **Parsear los headers con `split(",")`** y mapear `MS` → `ROLE_MS` | DEC-05 | `@PreAuthorize("hasRole('MS')")` no matchea y toda llamada micro→micro da 403 |
| **Emitir siempre `aud` y `scope`** en el token de servicio, tomándolo del campo `audience` del request | DEC-04 + DEC-17 | el `ServiceAudienceFilter` responde 403 a todo token de servicio sin `aud` |
| **Exponer `…/password/reset/confirm`** como segundo endpoint público | DEC-16 | el flujo de recuperación no se puede completar: hoy el manifiesto dibuja las dos mitades en el mismo `POST` |
| **Claves RS256 estables** (PEM montados, no generadas al arrancar) | DEC-18 | 🔴 con más de una instancia, `401` intermitentes y alternantes que parecen un problema del Gateway y no lo son |

---

## 15. Definition of Done

Del manifiesto §12, traducido a pruebas concretas.

| # | Criterio | Prueba |
|---|---|---|
| 1 | **(DEC-35)** Spring Boot 4.1.1 + Spring Cloud 2025.1.3 | `mvn dependency:tree` sin conflictos, y **sin downgrade** de `spring-boot-dependencies` por debajo de 4.1.1 |
| 1b | **(DEC-40)** `docker compose up` levanta todo; `users-service` **no** es alcanzable desde el host, el Gateway sí en `:8080` | `curl localhost:8082` → falla · `curl localhost:8080/api/users/public/...` → responde |
| 2 | `DiscoveryLocatorConfig` probado con **2 servicios reales** registrados en Eureka; el path final que recibe cada controller es el esperado | `DiscoveryAllowlistIT` — assert de que el backend recibe `/api/users/me`, **sin reescritura** |
| 3 | Un servicio **no admitido** en la `include-expression` responde **404** a través del Gateway — no queda expuesto ni parcialmente | `DiscoveryAllowlistIT` — registrar un `otro-service` en Eureka, pedir `/api/otro/x` → 404 |
| 4 | Confirmado **con pruebas de integración** el orden efectivo entre Spring Security y los `GlobalFilter` — **no asumirlo** por el orden declarado en código | `PipelineOrderIT` — filtros instrumentados que registran su secuencia real en un `List<String>` compartido |
| 5 | Un request a `/public/**` pasa sin token; el mismo path fuera de `/public/**` sin token da **401** | `PublicPrivateRouteIT` |
| 6 | Un token de servicio con **`aud` incorrecto** es rechazado **por el Gateway**, no solo por el microservicio | `ServiceAudienceIT` (**DEC-04**): token con `aud: cursos-service` contra `/api/users/profile/x` → **403**; el mismo con `aud: users-service` → pasa. Además: token de servicio **sin** `aud` → 403 |
| 7 | **(v5)** Un access token de persona con firma y `exp` válidos pero **`sid` desactualizado** es rechazado con **401** por el Gateway — no llega a rutearse | `SessionInvalidationIT` con Testcontainers Redis: write `session:{u}=sid-B`, presentar token con `sid-A` → 401 |
| 7b | **(DEC-01)** El fail-mode distingue causa | `SessionInvalidationIT`: key borrada → **401**; contenedor de Redis **detenido** → **503 + `Retry-After`**, nunca 401 ni 200 |
| 7c | **(DEC-07 + DEC-44)** Un token con `iss` distinto de `users-service`, o **sin** `iss`, da `401`; y el log deja una línea `JWT_RECHAZADO` **que nombra el claim** | `IssuerValidationIT` — los dos casos, más un assert sobre el contenido del log |
| 7d | **(DEC-44)** Un token de persona **sin** `est`/`pwd`/`onb` es rechazado y el log nombra el claim faltante | `AccountStateGuardIT` — es el caso "`users-service` viejo contra Gateway nuevo", que ahora falla de forma legible |
| 7e | **(DEC-23)** Un token de persona con `onb: true` recibe **403** en `/api/cursos/mis-cursos` y **pasa** en `GET /api/users/me`; un token de servicio con los mismos claims ausentes **no** es afectado por el filtro | `AccountStateGuardIT` — es el flujo de `manifiesto-flujos` §11, que hoy no es implementable |
| 7f | **(DEC-24)** Dos IPs distintas detrás del **mismo proxy** consumen buckets separados en `/api/users/public/auth/login` | `RateLimitForwardedIT` — sin esto el limitador trata a internet como un solo cliente (§9.9.1) |
| 7g | **(DEC-25)** Con Redis detenido, un request de persona sigue pasando durante la ventana de caché y **después** devuelve `503` (nunca `200` con sesión inválida) | `SessionCacheIT` con Testcontainers Redis — parar el contenedor y medir |
| 8 | El circuit breaker **abre** ante un destino caído y responde por `/fallback`; el rate limit responde **429 + `Retry-After`** al superar el límite | `ResilienceIT` — 429 ⚠️ parcialmente bloqueado por **TODO-03** (sin umbrales ni rutas caras) |
| 9 | Cada log del Gateway lleva el trace id visible; se puede seguir un request de punta a punta cruzando logs del Gateway y de un microservicio **con un solo id** | inspección del `logback-spring.xml` + assert sobre el appender en test |
| 10 | El `IdentityPropagationFilter` **borra** headers de identidad entrantes antes de inyectar | test unitario: mandar `X-User-Id: atacante` con un token válido de otra persona → el destino recibe el `sub` del token, no el header entrante |
| 11 | El Gateway **no** contiene ninguna decisión de rol | revisión de código: cero `hasRole` / `hasAuthority` / comparaciones contra `ADMIN`/`MS` fuera de `PrivateRouteGuard` (que solo verifica **forma**, no permiso) |

### Compatibilidad hacia adelante

Todo lo definido acá tiene que quedar **wireable sin retrabajo** cuando se construya `users-service`. El contrato de token (§8), los headers (§10) y la convención de rutas (§5) son el contrato que ese módulo va a consumir **tal cual**.

---

**Fuentes web consultadas** (para TODO-01 y §6.3, no para decisiones de diseño):
- [Spring Cloud 2025.1.2 (Oakwood) release](https://spring.io/blog/2026/06/11/spring-cloud-2025-1-2-aka-oakwood-has-been-released/)
- [Spring Cloud 2025.1.3 (Oakwood) release](https://spring.io/blog/2026/08/20/spring-cloud-2025-1-3-has-been-released/)
- [DiscoveryClient Route Definition Locator](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/the-discoveryclient-route-definition-locator.html)
- [Spring Cloud Gateway · common application properties](https://docs.spring.io/spring-cloud-gateway/reference/appendix.html)

## 16. Empaquetado y despliegue · **DEC-40**

Cierra `TODO-02` (acá) y `TODO-03` (`SPEC-users-service.md`). Los criterios ya estaban fijados por el manifiesto §12 y §06: **red privada, sin publicar los puertos de los micros**. Lo que faltaba era el archivo.

### 16.1 `Dockerfile` — el mismo patrón en los dos repos

```dockerfile
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline          # capa cacheable: no se invalida al tocar código
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app                                   # NO root
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
```

- **Multi-stage**: la imagen final no lleva Maven ni el código fuente.
- **`-DskipTests` es correcto acá**: los tests corren en el pipeline, no en el build de la imagen. Un `docker build` que necesita Testcontainers necesita un Docker adentro de Docker.
- **`MaxRAMPercentage`** en vez de `-Xmx`: respeta el límite del contenedor sin hardcodear un número.

### 16.2 `docker-compose.yml`

```yaml
services:
  eureka:
    image: steeltoeoss/eureka-server:latest   # TODO · reemplazar por el del equipo de infra
    networks: [tpi]

  mysql:
    image: mysql:8.4                          # DEC-20 · LTS
    environment:
      MYSQL_DATABASE: users
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}   # de .env, NUNCA del repo
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_0900_ai_ci
      --default-time-zone=+00:00
    volumes: [mysql-data:/var/lib/mysql]
    healthcheck:
      test: ["CMD","mysqladmin","ping","-h","localhost"]
      interval: 5s
      retries: 20
    networks: [tpi]

  redis:
    image: redis:7-alpine
    command: ["redis-server","--appendonly","yes"]   # persistencia: session:{userId} no tiene TTL
    volumes: [redis-data:/data]
    healthcheck:
      test: ["CMD","redis-cli","ping"]
      interval: 5s
      retries: 20
    networks: [tpi]

  kafka:
    image: apache/kafka:latest                # modo KRaft, sin Zookeeper
    networks: [tpi]

  users-service:
    build: ../users-service
    # SIN "ports:" A PROPOSITO (U11 / manifiesto §06). Es lo que hace
    # confiables los headers X-* : nadie puede saltearse el Gateway.
    expose: ["8082","8083"]                   # DEC-28
    environment:
      SPRING_DATASOURCE_URL: >-
        jdbc:mysql://mysql:3306/users?connectionTimeZone=UTC&preserveInstants=true
      JWT_PRIVATE_KEY_PATH: /run/secrets/jwt-private.pem   # DEC-18
      JWT_PUBLIC_KEYS_DIR:  /run/secrets/jwks
      JWT_ACTIVE_KID:       ${JWT_ACTIVE_KID}
    volumes:
      - ./secrets:/run/secrets:ro             # gitignoreado. NUNCA en el repo
    depends_on:
      mysql:   {condition: service_healthy}
      redis:   {condition: service_healthy}
    networks: [tpi]

  api-gateway:
    build: .
    ports: ["8080:8080"]                      # EL UNICO puerto publicado. DEC-28
    expose: ["8081"]                          # management, solo red interna
    environment:
      JWKS_URI: http://users-service:8082/.well-known/jwks.json
      REDIS_HOST: redis
    depends_on: [users-service, eureka]
    networks: [tpi]

networks:
  tpi:
    driver: bridge

volumes:
  mysql-data:
  redis-data:
```

### 16.3 Las cuatro decisiones que este archivo materializa

| Qué | Por qué |
|---|---|
| 🔴 **`users-service` no tiene `ports:`** | Es **lo que sostiene `DEC-08`**. Si ese puerto se publica, cualquiera puede pegarle directo con headers `X-User-Roles: ADMIN` inventados y saltearse el Gateway entero. La red privada no es una comodidad de despliegue: es el control de seguridad del que dependen todos los demás |
| **`--default-time-zone=+00:00`** en MySQL | `DEC-20` regla 2. Con `DATETIME(6)` la app escribe UTC; forzar la timezone del servidor elimina la última fuente de ambigüedad |
| **`--appendonly yes`** en Redis | `session:{userId}` **no tiene TTL** (§11): Redis no es un caché acá, es el registro de sesiones. Sin persistencia, un reinicio desloguea a todo el mundo — y con `DEC-01` fail-closed, responde `401` en masa. **El costo es despreciable:** con `appendfsync everysec` (default) Redis hace un `fsync` por segundo desde un hilo aparte, y nuestro régimen de **escrituras** es ~0,2/s (solo login, logout, refresh y 2FA — las lecturas del Gateway no escriben nada). El AOF va a pesar kilobytes. 🔴 **Requiere el volumen `redis-data:/data`: AOF sin volumen no sirve de nada** |
| **`./secrets` montado read-only y gitignoreado** | `DEC-18`. Las claves RS256 nunca entran a la imagen ni al repo |

> ⚠️ **`.env` con `MYSQL_ROOT_PASSWORD` y `JWT_ACTIVE_KID` va en `.gitignore`**, con un `.env.example` sin valores al lado. Es el error más común y el más caro de revertir: una vez que un secreto entró al historial de git, rotarlo es la única salida.

---
