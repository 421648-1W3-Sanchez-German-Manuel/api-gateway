---
description: Corre la regresión de sistema y explica qué significa cada rojo
---

> **Esto necesita el workspace de integración, no un repo solo.** El script
> habla con el stack completo por el puerto 8080, y el stack lo levanta el
> `docker-compose.yml` del subsistema, que se orquesta aparte (`DEC-40`). Si
> tenés un solo repositorio clonado, **tu loop de trabajo es `mvn -q clean verify`**:
> los tests de integración usan Testcontainers y se levantan solos, sin compose
> y sin los otros servicios. La regresión de sistema es el paso de integración.
>
> Si no tenés el workspace armado, decilo y frená ahí. No inventes un compose.

Corré `bash scripts/regresion.sh` desde la raíz del workspace de integración y
reportá el resultado.

Si el stack no está arriba o el dev-server del front no responde, decilo y
frená: sin ellos el script no puede sacar los códigos del outbox.

## Cómo leer los rojos

Un rojo no siempre es un bug. Antes de reportar nada, descartá estas tres, que
son comportamiento documentado del sistema y no fallas:

**1 · Un token recién emitido devuelve 401.** El Gateway cachea el estado de
sesión 3 segundos (`DEC-25`), así que durante esa ventana todavía tiene el `sid`
anterior de esa persona. Aplica a **todo** login, no solo al segundo. Si el
check no espera ~4 s después de loguear, falla de forma intermitente. La caché
existe para que un hipo de Redis no voltee la plataforma (`DEC-01` es
fail-closed): no es una optimización, es disponibilidad.

**2 · Después del logout el token viejo sigue andando unos segundos.** Misma
caché, mismo motivo. Medido: muere entre t=2s y t=4s.

**3 · Una ruta de ADMIN contesta `pending-account` en vez de lo que esperabas.**
La precedencia es: **gates de cuenta → conversión de parámetros → rol →
validación del cuerpo → lógica**. Con una cuenta que no está `ACTIVE`, el gate
contesta antes de que se llegue a mirar nada más. El check hay que hacerlo con
una cuenta habilitada.

## Qué sí es un bug

- Un error **sin `type`** en el cuerpo. Todos los errores de la plataforma son
  `application/problem+json` y el cliente ramifica por `type`. Un cuerpo
  `{timestamp, status, error, path}` es el default de Spring escapándose.
- Un **401 donde correspondía 403, 404 o 405**. Un 401 manda al login: si un
  typo de URL devuelve 401, un error del front desloguea a la persona.
- El `X-Request-Id` **no aparece en los logs de los dos servicios**. Si se corta
  en uno, la traza cruzada deja de servir.
- Cualquier check de la sección 8 sobre roles: ahí se prueba que un no-ADMIN no
  llega a lo que no le corresponde.

## Al terminar

Reportá el resumen (OK / fallan / salteados) y, si hay rojos, para cada uno:
qué esperaba, qué dio, y si cae en alguna de las tres trampas de arriba o es un
defecto real. No arregles nada sin decir primero qué encontraste.
