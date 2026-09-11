---
name: integrar-con-identidad
description: Usar al construir o modificar un microservicio que va detrás del API Gateway de la plataforma — recibir identidad por headers X-*, devolver errores, llamar a otro micro con token de servicio, entrar a la allowlist o escribir logs correlacionables. También al consumir la API desde un frontend.
---

# Integrarse detrás del API Gateway

Tu micro no valida tokens. El Gateway autentica, y te pasa quién es la persona
en headers. Todo lo de acá es contrato: si tu servicio no lo cumple, no rompe
solo tu tema — rompe la traza y el manejo de errores de toda la plataforma.

## ⛔ Antes que nada: tu micro NO publica puertos

En el `docker-compose.yml`, tu servicio lleva `expose:` y **nunca `ports:`**.

Tu micro confía en los headers `X-*` porque **solo el Gateway puede ponerlos**.
Con un puerto publicado, cualquiera hace esto y es administrador:

```bash
curl -X DELETE http://localhost:TU_PUERTO/api/tutema/algo -H "X-User-Roles: ADMIN"
```

Sin password, sin token, sin 2FA. **La ausencia de `ports:` es el control de
seguridad**, no una molestia para debuggear. Para mirar adentro:
`docker compose logs` y `docker compose exec`.

## Lo que te llega

El Gateway **borra** estos cinco headers del request entrante y después inyecta
los suyos, así que lo que te llega es confiable — y solo lo que él puso:

| Header | Cuándo viene | Qué es |
|---|---|---|
| `X-Principal-Type` | siempre | `user` o `service` |
| `X-User-Id` | token de persona | el `sub`, un UUID |
| `X-User-Roles` | token de persona | `STUDENT`, `PROFESSOR` o `ADMIN`, separados por coma sin espacio |
| `X-Service-Id` | token de servicio | qué micro llama |
| `X-Service-Scopes` | token de servicio | los scopes concedidos |

En una ruta **pública** llegan todos en `null`: el borrado se aplica igual, o
sería una ruta sin token donde cualquiera se declara ADMIN.

Con eso construís tu principal en un filtro. **No aceptes un request sin
`X-Principal-Type`**: significa que no vino por el Gateway.

## Lo que tenés que devolver

Todos los errores en `application/problem+json`, con `type` del espacio
`https://tpi.utn.frc/errors/`. El cliente ramifica por **`type`**, nunca por
status.

```json
{
  "type": "https://tpi.utn.frc/errors/access-denied",
  "title": "Acceso denegado",
  "status": 403,
  "detail": "No tiene permisos para esta operacion.",
  "instance": "/api/tutema/recurso",
  "requestId": "a8ed7668-f4f0-467a-9d33-3c87b0b0a813"
}
```

Reusá los `type` que ya existen antes de inventar uno; el catálogo está en
`references/contrato.md`. Y cubrí los que no se escriben a mano: 404 de ruta
inexistente, 405, parámetro que no convierte, cuerpo ilegible, y **el 401 y el
403 de la cadena de Security**, que no pasan por tu `@RestControllerAdvice`.

**Nunca devuelvas 401 por algo que no sea identidad.** Un 401 manda al login: si
un typo de URL da 401, un error del front desloguea a la persona.

## Logs: la línea que todos escribimos igual

Tu micro escribe **una línea por request**, con `requestId` y `traceId` en el
MDC:

```
[tu-servicio,83d0e9bd4f1177f62afb9d185a3d39c7,a8ed7668-…] GET /api/tutema/x -> 200 (12 ms)
```

Los dos ids llegan por header: `X-Request-Id` y `traceparent` (W3C Trace
Context, formato `00-{traceId}-{spanId}-{flags}`).

Esto **no es opcional ni es una convención nuestra**: si un solo micro no la
escribe, la traza se corta ahí y deja de servir para todos. El filtro son 20
líneas y está en `references/contrato.md`.

Validá los dos ids antes de usarlos: son entrada elegida por el cliente y
terminan en un archivo de log, donde un valor con saltos de línea fabrica
líneas falsas. Y **nunca loguees** bodies, tokens ni el header `Authorization`.

## Para llamar a otro micro

No hay llamadas directas entre servicios: A → Gateway → B, con un token de
servicio.

1. Pedí un `clientId` y un `clientSecret` al equipo de Identidad (uno por micro).
2. Pedí el token declarando **a quién** vas a llamar:
   `POST /api/users/public/auth/token` con `clientId`, `clientSecret`,
   **`grantType: "client_credentials"`**, el `scope` y `audience: el-micro-destino`.
3. Usalo contra el Gateway como cualquier `Authorization: Bearer`.

⚠️ **`grantType` no es opcional.** Es el campo que más se olvida, y cuando falta
la respuesta es `400 validation`, que se lee como "mis credenciales están mal" y
manda a buscar el problema donde no está. El cuerpo completo está en
`references/contrato.md`.

El `audience` acota el daño: si tu secreto se filtra, ese token sirve solo
contra ese destino. Un token pedido para `users-service` usado contra otro micro
recibe `403 invalid-audience`.

**El `clientSecret` jamás va en un frontend.**

## Para que el Gateway te descubra y te rutee

Son dos cosas distintas y las dos hacen falta.

**Descubrimiento — automático.** Te registrás en la misma Eureka
(`http://eureka:8761/eureka/` desde la red `tpi-platform`, o
`http://localhost:8761/eureka/` si corrés desde el IDE) con
`register-with-eureka: true`, `fetch-registry: false` y
`healthcheck.enabled: true`. A partir de ahí el Gateway resuelve tus instancias
solo: escalás, reiniciás o cambiás de IP y no hay que tocar nada.

**Exposición — NO automática, a propósito.** Registrarte no te expone: hasta que
tu `serviceId` esté en la allowlist del Gateway, `/api/tutema/**` devuelve 404.
Pedile al equipo de Identidad que te agregue, con el nombre exacto con el que te
registrás. Con doce equipos sumando servicios, el riesgo de exponer algo sin
querer pesa más que ahorrarse un trámite.

El checklist completo —nombre, red, readiness y cómo verificar cada paso— está
en `references/contrato.md`.

## Kafka es frontera

El broker no es de nadie en particular: los tópicos y los esquemas son contrato
acordado entre los equipos. Antes de publicar o consumir un evento, acordá el
nombre del tópico y el payload con el equipo dueño, y no cambies un esquema sin
avisar del otro lado.

Escribí tus eventos en una tabla outbox **en la misma transacción** que el
cambio de datos, y despachalos con un poller. Publicar directo a Kafka desde la
transacción hace que una caída del broker voltee todas tus escrituras.

## Antes de dar tu integración por terminada

Corré `/conformidad`: prueba lo que se puede comprobar desde afuera — que tu
micro no es alcanzable directo, que rechaza requests sin identidad, y que tus
errores traen `type`.
