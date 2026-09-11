# Contrato de integración · referencia

Detalle de lo que la skill resume. Lo normativo es esto.

## Idioma · antes de que preguntes

**La API HTTP de Identidad está en inglés**: rutas, campos JSON, valores de enum
y `type` de error. `/api/users/public/registration/student`, `accountStatus`,
`STUDENT`, `invalid-credentials`.

**Kafka no.** Los nombres de tópico (`tema-01-users.alumno-registrado.v1`), los
`eventType` (`ALUMNO_REGISTRADO`) y las claves del payload siguen la convención
que acordamos entre los diez subsistemas, y esa no es de un equipo solo. Si te
llega un evento nuestro, esperá las claves de siempre.

**El texto que ve la persona usuaria tampoco viaja por la API.** El `detail` de
un `problem+json` viene en inglés y es para tu log y tu debugging. La frase en
castellano la pone tu front, indexada por `type` — la tabla de abajo te da el
mapeo. No muestres el `detail` crudo en pantalla.

## Catálogo de `type` de error

Prefijo: `https://tpi.utn.frc/errors/`

| `type` | HTTP | Cuándo | Qué hace el cliente |
|---|---|---|---|
| `validation` | 400 | Datos mal formados o cuerpo ilegible | Mostrar `detail` junto al campo |
| `invalid-code` | 400 | Código de 6 dígitos incorrecto o vencido | Ofrecer reenviar |
| `invalid-link` | 400 | Enlace de un solo uso vencido, usado o inexistente | Ofrecer uno nuevo |
| `not-authenticated` | 401 | Sin identidad válida | Ir al login |
| `invalid-credentials` | 401 | Usuario o password incorrectos | Mensaje genérico |
| `session-superseded` | 401 | Hay un login más nuevo | "Iniciaste sesión en otro dispositivo" |
| `session-closed` | 401 | La sesión ya no existe | Volver a entrar |
| `pending-account` | 403 | La cuenta no está habilitada; trae `accountStatus` | Rutear según el estado |
| `password-change-required` | 403 | Debe cambiar la password | Pantalla de cambio |
| `onboarding-pending` | 403 | Falta completar el onboarding | Pantalla de onboarding |
| `access-denied` | 403 | Rol insuficiente | "No tenés permisos" |
| `invalid-audience` | 403 | Token de servicio usado contra otro destino | Bug de integración |
| `duplicate-email` | 409 | Ya existe | — |
| `invalid-transition` | 409 | Estado inconsistente | Recargar el estado |
| `too-many-attempts` | 429 | Rate limit; leer `Retry-After` | Mostrar la espera |
| `route-not-found` | 404 · 405 | La ruta no existe, o no con ese verbo | Bug del cliente |
| `service-unavailable` | 503 | Un micro o Redis no responde | **Reintentar, nunca ir al login** |

Los `type` propios de tu tema se agregan al catálogo y se avisan: un `type` que
el cliente no conoce es una pantalla de error genérica.

## El filtro de identidad y traza

Lo mismo que hace todo micro del ecosistema. En Spring, un `OncePerRequestFilter`
con `@Order(Ordered.HIGHEST_PRECEDENCE)`:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    /** Entrada elegida por el cliente que termina en un archivo de log:
     *  un valor con saltos de linea fabrica lineas falsas. */
    private static final Pattern ID_VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        long inicio = System.nanoTime();
        ponerEnMdc("requestId", req.getHeader("X-Request-Id"));
        ponerEnMdc("traceId", traceIdDe(req.getHeader("traceparent")));
        try {
            chain.doFilter(req, res);
        } finally {
            // Nunca el body ni el header Authorization.
            log.info("{} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(),
                    res.getStatus(), (System.nanoTime() - inicio) / 1_000_000);
            MDC.remove("requestId");
            MDC.remove("traceId");
        }
    }

    private void ponerEnMdc(String clave, String valor) {
        if (valor != null && ID_VALIDO.matcher(valor).matches()) MDC.put(clave, valor);
    }

    /** W3C Trace Context: 00-{traceId 32 hex}-{spanId 16 hex}-{flags} */
    private String traceIdDe(String traceparent) {
        if (traceparent == null) return null;
        String[] p = traceparent.split("-");
        return p.length >= 3 && p[1].matches("[0-9a-f]{32}") ? p[1] : null;
    }
}
```

Y el pattern de logging, para que los ids salgan en **todas** las líneas sin que
ninguna tenga que acordarse:

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{requestId:-}]"
```

> En un `logback-spring.xml` propio, `${spring.application.name}` **no** se
> resuelve contra el Environment de Spring: hay que declararlo con
> `<springProperty scope="context" name="appName" source="spring.application.name"/>`
> o el campo sale vacío.

## Construir el principal desde los headers

```java
String tipo = req.getHeader("X-Principal-Type");   // "user" | "service"
if (tipo == null) → 401 not-authenticated             // no vino por el Gateway

if ("user".equals(tipo)) {
    UUID userId = UUID.fromString(req.getHeader("X-User-Id"));
    var roles = Arrays.stream(req.getHeader("X-User-Roles").split(","))
                      .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
} else {
    String serviceId = req.getHeader("X-Service-Id");
    var scopes = req.getHeader("X-Service-Scopes");   // authorities sin prefijo
}
```

El rol se chequea en **tu** micro (`@PreAuthorize`): el Gateway autentica y
propaga, no autoriza por rol.

## Token de servicio

```jsonc
// POST /api/users/public/auth/token
{
  "clientId": "tu-servicio",
  "clientSecret": "...",             // solo en el servidor, jamás en un front
  "grantType": "client_credentials", // OBLIGATORIO. Sin esto: 400 validation
  "scope": "users.profile.read",
  "audience": "users-service"        // a quién vas a llamar
}
// -> { "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 300 }
```

> ⚠️ **`grantType` es obligatorio y es el error número uno de integración.**
> Falta en versiones viejas de este documento y en el manifiesto de flujos §08.
> Si te falta, la respuesta es `400 validation` con
> `"detail": "grantType: must not be blank"` — no es que tus credenciales estén
> mal. El único valor aceptado es `client_credentials`.

Dura 5 minutos y lleva `roles: ["MS"]`. El `scope` y el `audience` tienen que
ser coherentes: pedir un scope que deriva en otro destino se rechaza en la
emisión, no en el uso.

**El catálogo de scopes es cerrado.** Hoy el único emitible es
`users.profile.read`, que deriva en `audience: users-service`. Un scope que no
esté en el catálogo se rechaza al emitir. Sumar uno **no** es configuración: es
un cambio en `ScopeCatalog.java` y un despliegue de users-service, así que
pedilo con tiempo.

**El `clientId` y el `clientSecret` los emite Identidad**, uno por micro, con
`tpi-compose/scripts/seed-service-client.sh <clientId> <scopes>`. El secreto se
imprime una sola vez y no queda guardado: si se pierde, se regenera.

### Errores que vas a ver usando el token

| respuesta | qué pasó |
|---|---|
| `400 validation` | falta `grantType`, o un campo vacío |
| `401 invalid-credentials` | `clientId` o `clientSecret` mal, o cliente dado de baja |
| `403 invalid-audience` | pediste el token para otro destino |
| `403 access-denied` contra `/api/users/profile/{id}` | **también cuando el id no existe** — es anti-enumeración deliberada, no un problema de tus scopes. No hay 404 en esa ruta |

## Rutas y convenciones

- `/api/{tu-servicio}/public/**` — sin token.
- `/api/{tu-servicio}/**` — requiere token de persona o de servicio.
- El path **no se reescribe**: tu micro recibe la URL completa, con el prefijo.
- Un servicio caído devuelve **503 con `Retry-After`**, no 404: la ruta existe
  aunque la instancia no esté.

> Un detalle que confunde: una ruta inexistente da `404 route-not-found` **solo
> si el request trae un token válido**. Sin token da `401 not-authenticated`,
> porque la cadena de Security corre antes que el ruteo y todavía no sabe que la
> ruta no existe. Para un cliente logueado —que es el caso normal— vale la regla
> de siempre: un 401 es identidad y nada más.

## Registrarse y que te ruteen · el checklist completo

1. **Nombre.** `spring.application.name = {tu-servicio}`. De ahí sale tu path:
   se pasa a minúsculas y se le saca el sufijo `-service`, así que
   `cursos-service` → `/api/cursos/**`. Elegilo una vez y no lo cambies: el
   mismo string es tu `serviceId` en Eureka, tu entrada en la allowlist, tu
   `audience` y tu `X-Service-Id`.

2. **Registro.** Apuntá a la misma Eureka que el resto:

   ```yaml
   eureka:
     client:
       service-url:
         defaultZone: ${EUREKA_URL:http://eureka:8761/eureka/}
       register-with-eureka: true
       fetch-registry: false        # SOLO el Gateway tiene true
       healthcheck:
         enabled: true              # publica tu readiness, no el mero heartbeat
     instance:
       prefer-ip-address: true
   ```

   `fetch-registry: false` no es un descuido: vos no resolvés direcciones de
   nadie, hablás por el Gateway. `healthcheck.enabled: true` es lo que hace que
   una instancia a medio arrancar salga del balanceo en vez de recibir tráfico y
   contestar 500.

3. **Red.** En el compose, `expose:` y **nunca `ports:`**, en la red
   `tpi-platform`. Si levantás tu propio `docker-compose.yml`, declarala externa:

   ```yaml
   networks:
     tpi-platform:
       external: true
   ```

   La crea el stack de Identidad, así que ese tiene que estar arriba primero.
   Si en cambio corrés tu micro desde el IDE, Eureka está publicada en
   `http://localhost:8761/eureka/`.

4. **Readiness.** `GET /actuator/health/readiness` tiene que contestar, en el
   management port. Es lo que mira Eureka y lo que mira el compose.

5. **Pedir el alta en la allowlist.** Con el nombre exacto. Hasta que Identidad
   te agregue, `/api/{tu-servicio}/**` devuelve **404**, estés registrado o no.
   Registrarse y estar expuesto son dos cosas distintas, a propósito.

6. **Verificar.** Con el stack arriba, contra la puerta (`localhost:3000`):

   ```bash
   curl -s http://localhost:8761/eureka/apps | grep -o '<name>[^<]*</name>'   # ¿te registraste?
   curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/api/cursos/loquesea
   #   404 -> no estás en la allowlist
   #   503 -> estás en la allowlist pero sin instancias UP
   #   401 -> estás ruteado (falta el token, que es lo esperable sin uno)
   ```

## Estados de cuenta que vas a ver

Una cuenta que no está `ACTIVE` **no llega a tu micro**: el Gateway la corta con
`pending-account` y solo la deja hablar con `users-service`. No necesitás
manejar estados de cuenta; si te llega un request, esa persona está habilitada.
