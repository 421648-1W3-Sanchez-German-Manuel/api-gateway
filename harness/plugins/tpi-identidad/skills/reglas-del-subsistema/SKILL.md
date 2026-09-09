---
name: reglas-del-subsistema
description: Usar al escribir o modificar código de api-gateway o users-service — endpoints, filtros, seguridad, errores, gates de cuenta, eventos o docker-compose. Trae las reglas no negociables del subsistema de Identidad y las razones detrás de cada una.
---

# Reglas del subsistema de Identidad

Las decisiones de acá ya están tomadas y sostienen el modelo de seguridad. No
son preferencias de estilo: cada una tapa un agujero concreto. Si algo parece
una molestia arbitraria, la explicación está al lado.

## 1 · Ningún micro publica puertos

En `docker-compose.yml`, los micros llevan `expose:` y **nunca `ports:`**. Solo
el Gateway publica el 8080.

Los micros **no validan el JWT**: confían en los headers `X-*` que inyecta el
Gateway, precisamente porque nadie puede alcanzarlos sin pasar por él. Con un
puerto publicado, esto funciona sin password, sin token y sin 2FA:

```bash
curl -X DELETE http://localhost:8082/api/users/{id} -H "X-User-Roles: ADMIN"
```

**La ausencia de `ports:` ES el control de seguridad.** No es una molestia para
debuggear: para eso están `docker compose logs` y `docker compose exec`.

## 2 · Todo error sale en problem+json, con `type`

El cliente ramifica por **`type`**, nunca por status. Un error con el cuerpo por
defecto de Spring (`{timestamp, status, error, path}`) es un error que nadie
puede clasificar.

Eso incluye los que no se escriben a mano: ruta inexistente (404), verbo
equivocado (405), parámetro que no convierte (400), cuerpo ilegible (400), falta
de identidad (401) y rol insuficiente (403). Los dos últimos **no** pasan por el
`@RestControllerAdvice` — los maneja la cadena de Security, así que necesitan su
`authenticationEntryPoint` y su `accessDeniedHandler`.

Un `type` nuevo se agrega en `ErrorTypes` **y** se documenta en el handoff. Un
type que el front no conoce es una pantalla de error genérica.

**Nunca 401 por algo que no sea identidad.** Un 401 manda al login: si un typo
en una URL devuelve 401, un error del front desloguea a la persona.

## 3 · Los gates de cuenta, y la regla de las salidas

Tres gates: `ESTADO`, `PASSWORD`, `ONBOARDING`. Cada uno tiene **un endpoint que
es su salida**, y ese endpoint se exime de **los dos gates finos**, nunca solo
del propio.

Motivo: las dos cosas pueden estar pendientes a la vez — le pasa a todo ADMIN
nuevo, que nace con `mustChangePassword` y `firstLogin` en `true`. Si cada uno
eximiera únicamente su gate, se bloquean mutuamente y esa cuenta no entra nunca.

| Endpoint | ESTADO | PASSWORD | ONBOARDING |
|---|:--:|:--:|:--:|
| `GET /api/users/me` | exento | exento | exento |
| `POST /api/users/auth/logout` | exento | exento | exento |
| `POST /api/users/auth/password/change` | **aplica** | exento | exento |
| `PATCH /api/users/me/onboarding` | **aplica** | exento | exento |

Lo que no se relaja: el gate de **ESTADO**. Una cuenta que no está `ACTIVE` no
completa onboarding ni cambia la password.

**Un endpoint privado nuevo declara sus gates explícitamente**, aunque sea para
decir que no exime ninguno.

## 4 · El Gateway autentica; el micro autoriza

El Gateway valida firma, `exp`, `iss` y sesión, borra los cinco headers
reservados e inyecta los suyos. **No autoriza por rol** (R3).

El rol se chequea en el micro, con `@PreAuthorize`. Poner autorización por rol
en el Gateway obligaría a que conozca las reglas de negocio de todos los temas.

## 5 · Entre micros, siempre por el Gateway

Ninguna llamada directa entre servicios. Una llamada síncrona de A a B es
A → Gateway → B, con un token de servicio (`client_credentials`) que declara
`audience: B`.

El `aud` contiene el daño: si un secreto se filtra, el alcance queda limitado al
destino para el que ese token fue pedido, y no es una llave para toda la
plataforma.

Excepción: `auth/` y `users/` son módulos del **mismo** micro y se llaman por
método directo. Kafka tampoco pasa por el Gateway: publicar un evento no es una
llamada HTTP.

## 6 · Toda respuesta lleva traza, y todo micro la escribe

`X-Request-Id` y `traceparent` se propagan al destino, vuelven en la respuesta y
aparecen en `problem+json`. Cada micro escribe **una línea de log por request**
con los dos ids en el MDC:

```
[users-service,83d0e9bd4f1177f62afb9d185a3d39c7,FIN-230233] GET /api/users/me -> 200 (2 ms)
```

Si un solo micro no la escribe, la traza se corta ahí y el resto deja de servir.
Los dos ids **se validan antes de propagarse**: son entrada elegida por el
cliente y terminan en un archivo de log, donde un valor con saltos de línea
fabrica líneas falsas.

**Lo que jamás se loguea:** bodies, tokens, el header `Authorization`, ni el
`clientSecret`. Un token en el log es un token robado con solo esperar a que
alguien lea logs.

## 7 · Anti-enumeración

Estas respuestas son **idénticas** exista o no la cuenta, y ese es el punto:

- pedir un reset de password;
- reenviar un enlace de activación;
- un enlace o código vencido, ya usado o inventado;
- un login con email inexistente vs. password incorrecta.

Si al "mejorar el mensaje" empiezan a diferir, se convierte en un oráculo para
averiguar qué emails están registrados.

## 8 · Nada se borra

Baja lógica con `deleted_at`, en todas las tablas. Los chequeos de unicidad
miran las filas activas, no el histórico.

## 9 · Kafka es frontera, no es nuestro

El broker que corre en el compose es para poder probar. Los tópicos y los
esquemas son **contrato acordado con el equipo dueño**, y el de Cursos todavía
está sin cerrar: lo que hay es un borrador. No lo trates como definitivo ni
cambies un payload sin avisar del otro lado.

Los eventos se escriben en la tabla `outbox_events` **en la misma transacción**
que el cambio de datos, y un poller los despacha. Publicar directo a Kafka desde
la transacción hace que una caída del broker voltee todas las escrituras.

## Antes de dar algo por terminado

Corré `bash scripts/regresion.sh`. Los tests unitarios prueban que las piezas
funcionan; la regresión prueba que el sistema sigue en pie.
