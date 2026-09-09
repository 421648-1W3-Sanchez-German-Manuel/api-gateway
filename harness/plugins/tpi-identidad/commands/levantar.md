---
description: Levanta el stack completo del subsistema y espera a que esté realmente listo
---

> **Esto necesita el workspace de integración.** El `docker-compose.yml` del
> subsistema se orquesta aparte (`DEC-40`) y no viene en este repositorio. Si
> sólo tenés tu repo clonado, no hay stack que levantar: tu loop es
> `mvn -q verify`, que arma sus propios contenedores con Testcontainers. No
> escribas un compose para salir del paso — el del subsistema tiene reglas de
> seguridad que un compose improvisado no tiene, empezando por que ningún micro
> publica puertos.

Levantá el stack del Tema 01 y no digas que está listo hasta comprobarlo.

## Pasos

1. **Verificá que exista `api-gateway/.env`.** Si no está, copiá `.env.example` y
   avisá que hay que completar `MYSQL_ROOT_PASSWORD` y `JWT_ACTIVE_KID=dev`.
   Sin eso el arranque falla, y falla tarde.

2. **Verificá las claves RS256** en `users-service/secrets/`. Si no están, corré
   `users-service/scripts/gen-dev-keys.sh`. Sin ellas `users-service` **no
   arranca a propósito** (`DEC-18`): un servicio de identidad no debe levantar
   con claves improvisadas.

3. `docker compose up -d` desde `api-gateway/`.

4. **Esperá de verdad**, con un bucle sobre una condición, nunca con un `sleep`
   fijo:

   ```bash
   until curl -sf -o /dev/null http://localhost:8080/api/users/public/legal/terms; do sleep 4; done
   ```

5. Arrancá el front si lo van a usar: `node dev-server.mjs` desde `frontend/`.
   De ahí salen los códigos y enlaces del outbox — no hay servidor de mail.

6. Mostrá el estado final de los siete contenedores y confirmá que `mysql` y
   `redis` figuran *healthy*.

## Cuando algo no arranca

Antes de pegar un stack trace, mirá si es uno de estos. Los cuatro se parecen a
un bug y ninguno lo es:

- **MySQL tarda hasta 5 minutos la primera vez** (inicializa el data dir). Si se
  corta a la mitad, el volumen queda corrupto y hay que hacer
  `docker compose down -v`. **No cortar.**
- **El primer login después de levantar puede dar 503.** BCrypt costo 12 con la
  JVM en frío se pasa de los 3 s del breaker. Reintentar una vez alcanza. Si
  pasa siempre y no solo la primera vez, ahí sí es un problema real.
- **`users-service` con "No resolvable bootstrap urls"**: Kafka no está. Pasa si
  se levantó un servicio suelto en vez del stack. `docker compose up -d` entero.
- **404 en todo**: la tabla de rutas quedó vacía. Mirá el log de arranque del
  Gateway buscando `Ruta de la allowlist:` — tiene que haber una línea por cada
  servicio de `gateway.routing.allowlist`.

## Lo que NO se hace para debuggear

**No publiques los puertos de los micros.** `users-service` y `echo-service` no
tienen `ports:` a propósito: no validan el JWT, confían en los headers `X-*`
porque nadie puede alcanzarlos sin pasar por el Gateway. Con el 8082 publicado,
esto funciona sin password, sin token y sin 2FA:

```bash
curl -X DELETE http://localhost:8082/api/users/{id} -H "X-User-Roles: ADMIN"
```

Si necesitás mirar adentro, usá `docker compose logs` o `docker compose exec`.
