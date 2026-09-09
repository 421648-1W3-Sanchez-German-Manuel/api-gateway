---
description: Verifica que un microservicio cumple el contrato de integración del Gateway
argument-hint: [nombre-del-servicio]
---

Comprobá que el microservicio `$1` (o el del directorio actual, si no se indica)
cumple el contrato para operar detrás del API Gateway. Reportá cada punto como
cumple / no cumple, con la evidencia.

No arregles nada todavía: primero el diagnóstico completo.

## 1 · Frontera de red

```bash
grep -n "ports:" docker-compose.yml
```

**No puede haber `ports:` en el servicio.** Solo `expose:`. Si lo hay, es el
hallazgo más grave posible y va primero en el reporte: con el puerto publicado,
cualquiera es ADMIN mandando un header.

Comprobalo además desde afuera, con el stack levantado:

```bash
curl -sf --max-time 3 http://localhost:PUERTO/actuator/health && echo "ALCANZABLE" || echo "cerrado"
```

## 2 · Identidad

Buscá el filtro que construye el principal desde los headers `X-*`. Tiene que:

- rechazar con **401 `not-authenticated`** un request sin `X-Principal-Type`
  (significa que no vino por el Gateway);
- distinguir `user` de `service`;
- mapear `X-User-Roles` a authorities con prefijo `ROLE_`, separando por coma
  **sin** espacio;
- **no** volver a validar el JWT: eso ya lo hizo el Gateway.

Y que el rol se chequee en el micro (`@PreAuthorize` o equivalente), no en el
Gateway.

## 3 · Errores

Provocá los seis casos y verificá que **todos** traen `type`, `title`, `status`,
`instance` y `requestId`, con `Content-Type: application/problem+json`:

| Caso | Esperado |
|---|---|
| Ruta inexistente | 404 `route-not-found` |
| Verbo equivocado sobre una ruta que existe | 405 `route-not-found` |
| `{id}` que no convierte | 400 `validation` |
| Cuerpo JSON roto | 400 `validation` |
| Sin headers de identidad | 401 `not-authenticated` |
| Rol insuficiente | 403 `access-denied` |

Los dos últimos son los que más se escapan: los produce la cadena de Security,
no el `@RestControllerAdvice`, así que necesitan su propio
`authenticationEntryPoint` y `accessDeniedHandler`. Un cuerpo
`{timestamp, status, error, path}` es el default de Spring: no cumple.

## 4 · Traza

Mandá un request con un id conocido:

```bash
curl -s -o /dev/null http://localhost:8080/api/TUSERVICIO/loquesea \
  -H "X-Request-Id: CONFORMIDAD-001" \
  -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  -H "Authorization: Bearer $TOKEN"

docker compose logs TUSERVICIO --tail 20 | grep CONFORMIDAD-001
```

Tiene que aparecer **una línea por request** con el `requestId` y el `traceId`
—el mismo `4bf92f35…` que mandaste— y sin bodies, tokens ni `Authorization`.

Verificá también que un id hostil se descarta:

```bash
-H "X-Request-Id: falso INFO [servicio] LINEA-INYECTADA"
```

El id que vuelve tiene que ser uno generado, no el que mandaste.

## 5 · Llamadas a otros micros

Si el servicio llama a otro: que sea **por el Gateway**, con un token de
servicio que declare `audience`. Buscá `WebClient`/`RestClient` apuntando
directo a otro micro por su nombre de contenedor — eso es una llamada directa y
no cumple.

## 6 · Eventos

Si publica a Kafka: que escriba en una tabla **outbox en la misma transacción**
que el cambio de datos y despache con un poller, no directo desde la
transacción. Y que el tópico y el esquema estén acordados con el equipo dueño.

## Reporte

Al final, una tabla de cumple / no cumple, ordenada por gravedad: primero la
frontera de red, después identidad, después el resto. Para cada incumplimiento,
qué se observó y qué habría que cambiar.
