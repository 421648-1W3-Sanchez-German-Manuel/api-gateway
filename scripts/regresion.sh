#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Regresión del subsistema Identidad y Usuarios.
#
# Corre el contrato completo contra el stack levantado y dice qué pasó, caso
# por caso. Está pensado para correrse ENTERO después de cada cambio: es la
# diferencia entre "los tests unitarios pasan" y "el sistema sigue andando".
#
#   bash scripts/regresion.sh
#
# Requiere:
#   - el stack arriba          (api-gateway/docker compose up -d)
#   - el dev-server del front  (frontend/node dev-server.mjs) — de ahí salen
#     los códigos y enlaces del outbox, porque no hay servidor de mail
#
# Credenciales del ADMIN por variable de entorno, con default:
#   ADMIN_EMAIL=... ADMIN_PASS=... bash scripts/regresion.sh
# ---------------------------------------------------------------------------
set -uo pipefail

G=${G:-http://localhost:8080}
DEV=${DEV:-http://localhost:5173}
ADMIN_EMAIL=${ADMIN_EMAIL:-nuevo.admin@demo.utn.edu.ar}
ADMIN_PASS=${ADMIN_PASS:-claveNuevaSegura2026}
# Directorio del compose, para poder mirar los logs de los contenedores.
COMPOSE_DIR=${COMPOSE_DIR:-$HOME/OneDrive/Escritorio/dev/facultad/api-gateway}
STAMP=$(date +%H%M%S)

OK=0; FAIL=0; SKIP=0
FALLIDOS=()

# --- helpers ---------------------------------------------------------------

# ok <nombre> <esperado> <obtenido>
ok() {
  if [ "$2" = "$3" ]; then
    printf '  \033[32m✓\033[0m %-52s %s\n' "$1" "$3"; OK=$((OK+1))
  else
    printf '  \033[31m✗\033[0m %-52s esperaba %s, dio %s\n' "$1" "$2" "$3"
    FAIL=$((FAIL+1)); FALLIDOS+=("$1")
  fi
}

# okc <nombre> <esperado-substring> <texto>
okc() {
  if [[ "$3" == *"$2"* ]]; then
    printf '  \033[32m✓\033[0m %-52s contiene %s\n' "$1" "$2"; OK=$((OK+1))
  else
    printf '  \033[31m✗\033[0m %-52s no contiene %s\n' "$1" "$2"
    FAIL=$((FAIL+1)); FALLIDOS+=("$1")
  fi
}

skip() { printf '  \033[33m—\033[0m %-52s %s\n' "$1" "$2"; SKIP=$((SKIP+1)); }
sec()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

# status <method> <path> [body] [token]
status() {
  local m=$1 p=$2 b=${3:-} t=${4:-}
  local args=(-s -o /dev/null -w '%{http_code}' -X "$m" "$G$p")
  [ -n "$b" ] && args+=(-H 'Content-Type: application/json' -d "$b")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  curl "${args[@]}"
}

# body <method> <path> [body] [token]
body() {
  local m=$1 p=$2 b=${3:-} t=${4:-}
  local args=(-s -X "$m" "$G$p")
  [ -n "$b" ] && args+=(-H 'Content-Type: application/json' -d "$b")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  curl "${args[@]}"
}

json() { echo "$1" | grep -oE "\"$2\":\"[^\"]+" | head -1 | cut -d'"' -f4; }
tipo() { echo "$1" | grep -oE '"type":"[^"]+' | head -1 | sed 's|.*/||'; }

# outbox <email> <code|token>
# /dev/outbox NO devuelve el evento de Kafka: devuelve una proyeccion del
# dev-server que raspa el codigo del HTML ya renderizado. Esas claves son
# nuestras, asi que van en ingles.
outbox() {
  sleep 2
  curl -s "$DEV/dev/outbox?email=$1" | grep -oE "\"$2\":\"[^\"]+" | head -1 | cut -d'"' -f4
}

# login completo en dos fases -> imprime "accessToken refreshToken"
login() {
  local d
  d=$(json "$(body POST /api/users/public/auth/login "{\"email\":\"$1\",\"password\":\"$2\"}")" challengeId)
  [ -z "$d" ] && return 1
  local c; c=$(outbox "$1" code)
  local r; r=$(body POST /api/users/public/auth/2fa/verify "{\"challengeId\":\"$d\",\"code\":\"$c\"}")
  # DEC-25: el Gateway cachea el estado de sesion 3 s. Recien logueado, todavia
  # tiene cacheado el sid ANTERIOR de esta persona, asi que el token nuevo puede
  # dar 401 durante esa ventana. Sin esta espera los checks fallan de forma
  # intermitente y el sistema parece roto cuando esta bien.
  sleep 4
  echo "$(json "$r" accessToken) $(json "$r" refreshToken)"
}

# --- 0 · el stack responde -------------------------------------------------

sec "0 · Frontera de red y disponibilidad"
ok "el Gateway responde"           200 "$(status GET /api/users/public/legal/terms)"
ok "JWKS servido"                  200 "$(status GET /.well-known/jwks.json)"
curl -sf --max-time 3 http://localhost:8082/actuator/health >/dev/null 2>&1 \
  && ok "users-service inalcanzable" "cerrado" "abierto" \
  || ok "users-service inalcanzable" "cerrado" "cerrado"
curl -sf --max-time 3 http://localhost:8084/actuator/health >/dev/null 2>&1 \
  && ok "echo-service inalcanzable"  "cerrado" "abierto" \
  || ok "echo-service inalcanzable"  "cerrado" "cerrado"
if ! curl -sf --max-time 3 "$DEV/dev/mocks" >/dev/null 2>&1; then
  echo "  ⚠ el dev-server del front no responde en $DEV: sin él no hay codigos ni enlaces."
  exit 1
fi

# --- 1 · alta y activación por enlace (RF-USR-04) --------------------------

sec "1 · Alta y activación por enlace"
AL="reg.$STAMP@utn.edu.ar"
ok "alta de alumno" 200 "$(status POST /api/users/public/registration/student \
  "{\"firstNames\":\"Reg\",\"lastNames\":\"Test\",\"legajo\":\"9$STAMP\",\"email\":\"$AL\",
    \"password\":\"passwordvalida1\",\"invitationCode\":\"PROG4-2026-A1\",\"termsVersion\":\"v1\"}")"

TOK=$(outbox "$AL" token)
[ -n "$TOK" ] && ok "el mail trae un enlace, no un code" "si" "si" \
              || ok "el mail trae un enlace, no un code" "si" "no"

ok "activar con el enlace" 200 "$(status POST /api/users/public/registration/activate "{\"token\":\"$TOK\"}")"

# El de arriba ya lo consumio: este es el segundo uso.
R2=$(body POST /api/users/public/registration/activate "{\"token\":\"$TOK\"}")
okc "el enlace se usa una sola vez" "invalid-link" "$(tipo "$R2")"

R3=$(body POST /api/users/public/registration/activate '{"token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}')
ok "usado e inventado dan la MISMA respuesta" "$(echo "$R2" | grep -oE '"detail":"[^"]+')" \
                                              "$(echo "$R3" | grep -oE '"detail":"[^"]+')"

okc "email duplicado" "duplicate-email" "$(tipo "$(body POST /api/users/public/registration/student \
  "{\"firstNames\":\"R\",\"lastNames\":\"T\",\"legajo\":\"1\",\"email\":\"$AL\",
    \"password\":\"passwordvalida1\",\"invitationCode\":\"X\",\"termsVersion\":\"v1\"}")")"

okc "password corta" "validation" "$(tipo "$(body POST /api/users/public/registration/student \
  "{\"firstNames\":\"R\",\"lastNames\":\"T\",\"legajo\":\"2\",\"email\":\"corta.$STAMP@utn.edu.ar\",
    \"password\":\"corta\",\"invitationCode\":\"X\",\"termsVersion\":\"v1\"}")")"

okc "TyC no aceptados" "validation" "$(tipo "$(body POST /api/users/public/registration/student \
  "{\"firstNames\":\"R\",\"lastNames\":\"T\",\"legajo\":\"3\",\"email\":\"tyc.$STAMP@utn.edu.ar\",
    \"password\":\"passwordvalida1\",\"invitationCode\":\"X\",\"termsVersion\":\"v999\"}")")"

okc "profesor fuera de la whitelist" "email-not-whitelisted" "$(tipo "$(body POST /api/users/public/registration/professor \
  "{\"firstNames\":\"P\",\"lastNames\":\"T\",\"email\":\"nowl.$STAMP@utn.edu.ar\",
    \"password\":\"passwordvalida1\",\"termsVersion\":\"v1\"}")")"

M1=$(body POST /api/users/public/registration/resend-activation "{\"email\":\"nadie.$STAMP@utn.edu.ar\"}")
M2=$(body POST /api/users/public/registration/resend-activation "{\"email\":\"$AL\"}")
ok "reenvio anti-enumeracion" "$M1" "$M2"

# --- 2 · login en dos fases ------------------------------------------------

sec "2 · Login en dos fases"
okc "password incorrecta" "invalid-credentials" \
  "$(tipo "$(body POST /api/users/public/auth/login "{\"email\":\"$AL\",\"password\":\"noesladelaquehablamos\"}")")"

D=$(json "$(body POST /api/users/public/auth/login "{\"email\":\"$AL\",\"password\":\"passwordvalida1\"}")" challengeId)
[ -n "$D" ] && ok "fase 1 devuelve challengeId" "si" "si" || ok "fase 1 devuelve challengeId" "si" "no"

okc "code 2FA incorrecto" "invalid-code" \
  "$(tipo "$(body POST /api/users/public/auth/2fa/verify "{\"challengeId\":\"$D\",\"code\":\"000000\"}")")"

C=$(outbox "$AL" code)
TOKENS=$(body POST /api/users/public/auth/2fa/verify "{\"challengeId\":\"$D\",\"code\":\"$C\"}")
AT=$(json "$TOKENS" accessToken); RT=$(json "$TOKENS" refreshToken)
[ -n "$AT" ] && ok "fase 2 devuelve tokens" "si" "si" || ok "fase 2 devuelve tokens" "si" "no"

ME=$(body GET /api/users/me "" "$AT")
okc "GET /me responde"          "$AL"              "$ME"
okc "el alumno queda PENDING_COURSE" "PENDING_COURSE" "$ME"

# --- 3 · los gates ---------------------------------------------------------

sec "3 · Gates de cuenta"
okc "cuenta pendiente NO alcanza otro micro" "pending-account" \
  "$(tipo "$(body GET /api/echo/quien-soy "" "$AT")")"
ok  "cuenta pendiente SI alcanza /me" 200 "$(status GET /api/users/me "" "$AT")"

# --- 4 · sesión: refresh, rotación, única -----------------------------------

sec "4 · Sesión"
NT=$(body POST /api/users/public/auth/refresh "{\"refreshToken\":\"$RT\"}")
ok  "refresh devuelve un par nuevo" 200 "$(status POST /api/users/public/auth/refresh "{\"refreshToken\":\"$(json "$NT" refreshToken)\"}")"
okc "reusar un refresh rotado" "session-closed" \
  "$(tipo "$(body POST /api/users/public/auth/refresh "{\"refreshToken\":\"$RT\"}")")"

# El reuso mata la familia: hay que re-loguear para seguir.
read -r AT RT <<< "$(login "$AL" passwordvalida1)"
ok "logout" 200 "$(status POST /api/users/auth/logout "" "$AT")"
# DEC-25: el Gateway cachea el estado de sesion 3 s. Sin esta espera el token
# viejo TODAVIA anda y el check parece fallar cuando el sistema esta bien.
sleep 4
okc "el token muere con el logout" "session-closed" "$(tipo "$(body GET /api/users/me "" "$AT")")"

# --- 5 · errores: TODOS con type -------------------------------------------

sec "5 · Contrato de errores"
read -r AT RT <<< "$(login "$AL" passwordvalida1)"
okc "ruta inexistente -> 404 route-not-found" "route-not-found" \
  "$(tipo "$(body GET /api/users/no/existe/tampoco "" "$AT")")"
ok  "  y el status es 404" 404 "$(status GET /api/users/no/existe/tampoco "" "$AT")"
okc "verbo equivocado -> 405 con type"  "route-not-found" "$(tipo "$(body GET /api/users/no-existe "" "$AT")")"
ok  "  y el status es 405" 405 "$(status GET /api/users/no-existe "" "$AT")"
# El id mal formado se prueba en la seccion 8: con una cuenta PENDING_COURSE
# contesta antes el gate de ESTADO y nunca se llega a convertir el {id}.
okc "JSON roto -> validation"           "validation" \
  "$(tipo "$(curl -s -X POST $G/api/users/public/auth/login -H 'Content-Type: application/json' -d '{roto')")"
okc "prefijo fuera de la allowlist"     "route-not-found" "$(tipo "$(body GET /api/nope/x "" "$AT")")"
okc "sin token -> not-authenticated"       "not-authenticated"   "$(tipo "$(body GET /api/users/me)")"

# --- 5b · trazabilidad ------------------------------------------------------

sec "5b · Trazabilidad"
RID="REGRESION-$STAMP"
DEVUELTO=$(curl -s -o /dev/null -D - "$G/api/users/public/legal/terms" -H "X-Request-Id: $RID"   -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"   | grep -i "^x-request-id:" | tr -d "
" | awk "{print \$2}")
ok "el X-Request-Id vuelve en la respuesta" "$RID" "$DEVUELTO"

# Un id con espacios y texto de log NO puede entrar: terminaria en el archivo
# de log y fabricaria lineas falsas.
HOSTIL=$(curl -s -o /dev/null -D - "$G/api/users/public/legal/terms"   -H "X-Request-Id: falso INFO [api-gateway] LINEA-INYECTADA"   | grep -i "^x-request-id:" | tr -d "
" | awk "{print \$2}")
if [ "$HOSTIL" = "falso" ] || [[ "$HOSTIL" == *"INYECTADA"* ]]; then
  ok "un requestId hostil se descarta" "generado" "aceptado"
else
  ok "un requestId hostil se descarta" "generado" "generado"
fi

if command -v docker >/dev/null 2>&1 && [ -n "${COMPOSE_DIR:-}" ]; then
  sleep 2
  EN_GW=$(docker compose -f "$COMPOSE_DIR/docker-compose.yml" logs api-gateway --tail 40 2>&1 | grep -c "$RID")
  EN_MS=$(docker compose -f "$COMPOSE_DIR/docker-compose.yml" logs users-service --tail 40 2>&1 | grep -c "$RID")
  [ "$EN_GW" -gt 0 ] && ok "el id aparece en el log del Gateway" "si" "si"                      || ok "el id aparece en el log del Gateway" "si" "no"
  [ "$EN_MS" -gt 0 ] && ok "y en el del microservicio" "si" "si"                      || ok "y en el del microservicio" "si" "no"
else
  skip "el id en los logs de los dos servicios" "definir COMPOSE_DIR para chequearlo"
fi


# --- 6 · integración con echo-service --------------------------------------

sec "6 · Integración de tres servicios"
skip "checks de echo con este alumno" "requiere cuenta ACTIVE (ver seccion 8)"

# --- 7 · reset de password --------------------------------------------------

sec "7 · Reset de password"
P1=$(body POST /api/users/public/auth/password/reset "{\"email\":\"nadie.$STAMP@utn.edu.ar\"}")
P2=$(body POST /api/users/public/auth/password/reset "{\"email\":\"$AL\"}")
ok "respuesta anti-enumeracion" "$P1" "$P2"
RTOK=$(outbox "$AL" token)
ok "confirmar el reset" 200 "$(status POST /api/users/public/auth/password/reset/confirm \
  "{\"token\":\"$RTOK\",\"newPassword\":\"otrapasswordvalida1\"}")"
D=$(json "$(body POST /api/users/public/auth/login "{\"email\":\"$AL\",\"password\":\"otrapasswordvalida1\"}")" challengeId)
[ -n "$D" ] && ok "login con la password nueva" "si" "si" || ok "login con la password nueva" "si" "no"

# --- 8 · operaciones de ADMIN ----------------------------------------------

sec "8 · ADMIN, whitelist y micro↔micro"
read -r ADT ADR <<< "$(login "$ADMIN_EMAIL" "$ADMIN_PASS")"
if [ -z "${ADT:-}" ]; then
  skip "seccion completa de ADMIN" "no se pudo loguear $ADMIN_EMAIL (ver ADMIN_EMAIL/ADMIN_PASS)"
else
  ok "login del ADMIN" 200 "$(status GET /api/users/me "" "$ADT")"

  NUEVO="creado.$STAMP@demo.utn.edu.ar"
  CR=$(body POST /api/users "{\"firstNames\":\"Cre\",\"lastNames\":\"Ado\",\"email\":\"$NUEVO\",
        \"password\":\"passwordvalida1\",\"role\":\"STUDENT\"}" "$ADT")
  NID=$(json "$CR" id)
  [ -n "$NID" ] && ok "crear usuario" "si" "si" || ok "crear usuario" "si" "no"
  ok "cambiar role" 200 "$(status PATCH "/api/users/$NID/role" '{"role":"PROFESSOR"}' "$ADT")"

  WL="wl.$STAMP@demo.utn.edu.ar"
  ok "agregar a la whitelist" 200 "$(status POST /api/users/whitelist "{\"email\":\"$WL\"}" "$ADT")"
  okc "  y aparece al listar" "$WL" "$(body GET /api/users/whitelist "" "$ADT")"

  # El alta de profesor ahora SI tiene que pasar, y activarse por enlace.
  ok "alta de profesor con el email habilitado" 200 "$(status POST /api/users/public/registration/professor \
    "{\"firstNames\":\"Profe\",\"lastNames\":\"Test\",\"email\":\"$WL\",
      \"password\":\"passwordvalida1\",\"termsVersion\":\"v1\"}")"
  PTOK=$(outbox "$WL" token)
  ok "activar al profesor" 200 "$(status POST /api/users/public/registration/activate "{\"token\":\"$PTOK\"}")"

  read -r PT PR <<< "$(login "$WL" passwordvalida1)"
  okc "el profesor queda ACTIVE" "ACTIVE" "$(body GET /api/users/me "" "$PT")"

  # Onboarding: es lo que habilita al profesor a salir de los gates.
  ok "onboarding del profesor" 200 "$(status PATCH /api/users/me/onboarding \
    '{"githubUsername":"profe-dev","avatarRef":null,"tourOk":true}' "$PT")"
  read -r PT PR <<< "$(login "$WL" passwordvalida1)"

  SOL=$(body POST /api/users/whitelist/requests \
    "{\"email\":\"otro.$STAMP@demo.utn.edu.ar\",\"reason\":\"regresion\"}" "$PT")
  SID=$(json "$SOL" id)
  [ -n "$SID" ] && ok "el profesor pide habilitacion" "si" "si" || ok "el profesor pide habilitacion" "si" "no"
  ok "el admin la resuelve" 200 "$(status PATCH "/api/users/whitelist/requests/$SID" \
    '{"approve":true,"rejectionReason":null}' "$ADT")"
  okc "un no-ADMIN no entra a /api/users" "access-denied" \
    "$(tipo "$(body GET /api/users/whitelist "" "$PT")")"
  ok  "  y ese 403 sale en problem+json" 403 "$(status GET /api/users/whitelist "" "$PT")"
  okc "id que no es UUID -> validation" "validation" \
    "$(tipo "$(body PATCH /api/users/no-es-uuid/role '{"role":"STUDENT"}' "$PT")")"

  # --- integración de tres servicios, con una cuenta habilitada ---
  QS=$(body GET /api/echo/quien-soy "" "$PT")
  okc "la identidad llega a otro micro"     "X-User-Id"        "$QS"
  okc "  con el role correcto"               "PROFESSOR"         "$QS"
  okc "  y sin headers de servicio"         "\"X-Service-Id\":null" "$QS"
  PID=$(json "$(body GET /api/users/me "" "$PT")" id)
  CP=$(body GET "/api/echo/cliente/perfil/$PID" "" "$PT")
  okc "ciclo micro-micro: token de servicio" "obtenido"        "$CP"
  if [[ "$CP" == *"\"email\""* ]]; then
    ok "  el perfil publico NO expone email" "reducido" "expone email"
  else
    ok "  el perfil publico NO expone email" "reducido" "reducido"
  fi
  okc "el aud contiene el dano"              "RECHAZADO"       "$(body GET /api/echo/cliente/probar-aud-cruzado "" "$PT")"
  ok  "ruta interna con token de persona"    401 "$(status GET /api/echo/interno "" "$PT")"

  # --- el deadlock de gates (RF-USR-01) ---
  ADM2="admin2.$STAMP@demo.utn.edu.ar"
  body POST /api/users "{\"firstNames\":\"Adm\",\"lastNames\":\"Dos\",\"email\":\"$ADM2\",
        \"password\":\"passwordvalida1\",\"role\":\"ADMIN\"}" "$ADT" >/dev/null
  read -r A2T A2R <<< "$(login "$ADM2" passwordvalida1)"
  if [ -z "${A2T:-}" ]; then
    skip "deadlock de gates" "no se pudo loguear el ADMIN recien creado"
  else
    okc "el ADMIN nuevo debe cambiar password" "\"mustChangePassword\":true" "$(body GET /api/users/me "" "$A2T")"
    ok "  cambia la password con onboarding pendiente" 200 "$(status POST /api/users/auth/password/change \
      '{"currentPassword":"passwordvalida1","newPassword":"claveNuevaSegura2026"}' "$A2T")"
    read -r A2T A2R <<< "$(login "$ADM2" claveNuevaSegura2026)"
    ok "  y completa el onboarding" 200 "$(status PATCH /api/users/me/onboarding \
      '{"githubUsername":"admin2-dev","avatarRef":null,"tourOk":true}' "$A2T")"
  fi
fi

# --- resumen ---------------------------------------------------------------

printf '\n\033[1m─────────────────────────────────────────────\033[0m\n'
printf '  \033[32m%d OK\033[0m · \033[31m%d fallan\033[0m · \033[33m%d salteados\033[0m\n' "$OK" "$FAIL" "$SKIP"
if [ "$FAIL" -gt 0 ]; then
  printf '\n  Fallaron:\n'
  for f in "${FALLIDOS[@]}"; do printf '    · %s\n' "$f"; done
  exit 1
fi
