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
  "clientSecret": "...",          // solo en el servidor, jamás en un front
  "scope": "users.profile.read",
  "audience": "users-service"     // a quién vas a llamar
}
// -> { "accessToken": "eyJ...", "expiresIn": 300 }
```

Dura 5 minutos y lleva `roles: ["MS"]`. El `scope` y el `audience` tienen que
ser coherentes: pedir un scope que deriva en otro destino se rechaza en la
emisión, no en el uso.

## Rutas y convenciones

- `/api/{tu-servicio}/public/**` — sin token.
- `/api/{tu-servicio}/**` — requiere token de persona o de servicio.
- El path **no se reescribe**: tu micro recibe la URL completa, con el prefijo.
- Un servicio caído devuelve **503 con `Retry-After`**, no 404: la ruta existe
  aunque la instancia no esté.

## Estados de cuenta que vas a ver

Una cuenta que no está `ACTIVE` **no llega a tu micro**: el Gateway la corta con
`pending-account` y solo la deja hablar con `users-service`. No necesitás
manejar estados de cuenta; si te llega un request, esa persona está habilitada.
