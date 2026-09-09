---
description: Trae de la base el último código 2FA o enlace de activación de un email
argument-hint: <email>
---

Traé el último código o enlace que la plataforma le "envió" a `$1`.

En desarrollo **no hay servidor de mail**: los mensajes se encolan en la tabla
`outbox_events` y de ahí salen. Es lo que permite completar un login o una
activación sin una casilla real.

## La vía corta

Si el dev-server del front está corriendo:

```bash
curl -s "http://localhost:5173/dev/outbox?email=$1"
```

Devuelve las últimas notificaciones de ese email con el `code` (6 dígitos, es
el 2FA) y el `token` (el del enlace de activación o de reset) ya extraídos.

## La vía directa, si el front no está levantado

```bash
cd api-gateway && set -a && . ./.env && set +a
docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" users -N \
  -e "SELECT REPLACE(payload,'\n',' ') FROM outbox_events
      ORDER BY created_at DESC LIMIT 5;"
```

La tabla se llama `outbox_events` y la columna del tópico es `topic`, no
`event_type`.

De ahí salen los dos formatos:

- **Código de 2FA**: seis dígitos, dentro de `<strong>…</strong>`. Vence en 5
  minutos y admite 5 intentos.
- **Enlace de activación o de reset**: `token=…` dentro del `href`. El de
  activación vive 24 h; el de reset, 15 minutos. Los dos son de un solo uso, y
  pedir uno nuevo invalida el anterior.

## Ojo con esto

Si hay varios registros para el mismo email, **el bueno es el más reciente**:
cada reenvío invalida el anterior. Un código o enlace viejo devuelve el mismo
error que uno inventado — es anti-enumeración, no falta de detalle.

Reportá el valor encontrado y de qué tipo es, para que quien lo pidió sepa
dónde va.
