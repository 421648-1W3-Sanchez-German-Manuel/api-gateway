# Harness · Tema 01

Dos plugins de Claude Code, en un marketplace local. Se instalan una vez y todos
trabajan con el mismo criterio.

| Plugin | Para quién | Qué trae |
|---|---|---|
| `tpi-identidad` | los que trabajan sobre `api-gateway` y `users-service` | `/levantar`, `/regresion`, `/code` + las reglas no negociables del subsistema |
| `tpi-integracion` | los equipos de **otros temas** que se integran detrás del Gateway | `/conformidad` + el contrato completo de integración |

## Instalación

```
/plugin marketplace add ~/ruta/al/TPI/Manifiestosv1/harness
/plugin install tpi-identidad
```

Los equipos de otros temas instalan el otro:

```
/plugin install tpi-integracion
```

Cuando el TP viva en un repositorio, la primera línea pasa a ser la URL del
repo y la actualización es automática para todos.

## Qué hace cada comando

**`/levantar`** — levanta el stack y **espera a que esté realmente listo**, no a
que el comando termine. Chequea el `.env` y las claves RS256 antes, porque sin
ellos el arranque falla tarde y confuso. Y conoce los cuatro arranques que
parecen un bug y no lo son.

**`/regresion`** — corre los 64 checks de `scripts/regresion.sh` y, si hay
rojos, distingue un defecto real de las tres trampas de timing del sistema (la
caché de sesión de 3 s tras un login, la misma tras el logout, y la precedencia
de los gates sobre la conversión de parámetros). Eso es lo que evita perder
media hora persiguiendo un fantasma.

**`/code <email>`** — trae el último código 2FA o enlace de activación de la
tabla `outbox_events`. En desarrollo no hay servidor de mail: sin esto no se
completa un login.

**`/conformidad`** — para los otros temas: verifica desde afuera que su micro
cumple el contrato. Deja de ser "leé el documento" y pasa a ser "corré esto y
mostrame el verde".

## Las skills

No se invocan a mano: se activan solas cuando alguien toca el código que les
corresponde.

**`reglas-del-subsistema`** — las nueve reglas que sostienen el modelo de
seguridad, con el porqué de cada una. Se dispara al tocar endpoints, filtros,
seguridad, errores, gates, eventos o el compose.

**`integrar-con-identidad`** — el contrato para los otros temas: headers de
identidad, errores, la línea de log que todos escriben igual, tokens de servicio
y allowlist.

## Las reglas que fallan solas

Lo que no depende de que nadie recuerde nada. Corren con `mvn test`:

| Test | Qué frena |
|---|---|
| `NetworkBoundaryTest` (gateway) | Que alguien agregue `ports:` a un micro en el compose. Con el puerto publicado, cualquiera es ADMIN mandando un header |
| `GateExemptionsTest` (users) | Que un endpoint de salida de un gate exima solo el propio, o que una operación de ADMIN exima alguno |

Está probado que fallan cuando la regla se rompe, no solo que pasan cuando se
cumple: publicar el 8082 en el compose rompe el build con el mensaje que explica
por qué.

Una convención escrita se ignora en la semana dos. Una que rompe el build, no.

## Por qué un plugin y no skills sueltas

Con skills sueltas cada uno las copia a mano y las mantiene al día por su
cuenta; a la segunda semana hay cuatro versiones distintas. Un plugin se
instala con una orden, se actualiza solo, y **trae los slash commands**, que son
la parte que más se usa.
