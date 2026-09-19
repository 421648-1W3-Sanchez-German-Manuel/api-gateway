# api-gateway — operational reference

How the gateway works, what it needs from a service that wants to be routed
through it, and every knob it exposes. The reasoning behind each decision lives
in [SPEC-api-gateway.md](SPEC-api-gateway.md) (`DEC-xx`); this file is the
short, factual version.

---

## 1. Position in the topology

```
browser ──▶ nginx :3000 ──▶ api-gateway :8080 ──▶ lb://{service-id}
                (same origin)      │                  (Eureka)
                                   ├──▶ Redis        (session:{userId}, read only)
                                   └──▶ users-service JWKS (signature keys)
```

- **The gateway is the only process that publishes a port.** Microservices use
  `expose:` and never `ports:`. That is precisely what makes it safe for them to
  trust the `X-*` identity headers: nothing can reach them except through here.
- nginx proxies only `/api/`, `/.well-known/` and `/dev/` to the gateway.
  Anything else falls into the Angular SPA.
- Ports: **8080** traffic, **8081** management (`/actuator/health`, `/info`,
  `/prometheus`).
- There is **no CORS configuration on purpose**: front and API share the origin
  `:3000`, so there is no preflight. If the gateway is ever exposed directly,
  CORS has to be implemented then — an `OPTIONS` without `Authorization` dies on
  the guards with 401.

---

## 2. Request lifecycle

Two different orderings are in play, and they are not interchangeable:

| # | Component | Kind / order | What it does |
|---|---|---|---|
| 1 | `SecurityConfig` chain | `WebFilter` @ -100 | Extracts the raw JWT, decodes it, validates signature + `exp` + `iss`. Public patterns are `permitAll`. |
| 2 | `SessionGuard` | `WebFilter` @ 0 | Single session: `sid` of the token vs `session:{userId}` in Redis. Skips public routes by asking `PublicRouteMatcher` itself. |
| 3 | `CorrelationIdFilter` | `GlobalFilter` @ 10 | `X-Request-Id` and `traceparent`, preserved or generated. |
| 4 | `LoggingFilter` | `GlobalFilter` @ 20 | One line per request. Never bodies, tokens or `Authorization`. |
| 5 | `PublicRouteGuard` | `GlobalFilter` @ 30 | Marks the exchange public/private (`/api/*/public/**`). |
| 6 | `PrivateRouteGuard` | `GlobalFilter` @ 40 | Token **shape**: `type`, `sub`, `roles`, and channel (cookie vs header). |
| 7 | `AccountStateGuard` | `GlobalFilter` @ 50 | Coarse account-state gate (`est`, `pwd`, `onb`). |
| 8 | `ServiceAudienceFilter` | `GlobalFilter` @ 60 | `aud` of a service token vs the resolved destination. |
| 9 | `IdentityPropagationFilter` | `GlobalFilter` @ 70 | Strips the five reserved headers, injects the derived ones. |
| 10 | `InterMicroTraceFilter` | `GlobalFilter` @ 75 | Dev-only trace into Redis. Best effort, never blocks. |
| 11 | `RateLimitFilter` | `GlobalFilter` @ 80 | Token bucket on the configured expensive routes. |
| 12 | `BulkheadFilter` | `GlobalFilter` @ 90 | One semaphore per destination service. |
| 13 | `Retry` → `CircuitBreaker` | route filters | `default-filters`: retry (GET only) inside the breaker; fallback to `/fallback/servicio`. |

Facts that are part of the contract, not implementation detail:

- **Spring Security runs before every `GlobalFilter`.** The guards hang off an
  already validated `Jwt`; without that premise none of them mean anything.
- **All `WebFilter`s run before any `GlobalFilter`.** That is why `SessionGuard`
  (step 2) is mechanically ahead of `PublicRouteGuard` (step 5), even though the
  logical reading is the other way round. `SessionGuard` therefore cannot read
  `PublicRouteGuard`'s attribute and consults `PublicRouteMatcher` directly —
  both paths share one definition of "public".
- **The route is already resolved when the `GlobalFilter` chain starts**, so
  step 8 and step 12 can read the destination.
- **Step 8 runs before step 9**, always. Injecting identity headers from a token
  whose `aud` has not been checked would break anti-spoofing.
- `PipelineOrderIT` pins all of the above. It verifies the order the filters
  *actually* run in, not the one they declare.

---

## 3. Routing and the allowlist

### The rule

Routes are generated **in Java** by `AllowlistRouteLocator`, one per entry of
`gateway.routing.allowlist`:

```
users-service  ──▶  id=allowlist-users-service
                    uri=lb://users-service
                    Path=/api/users/**
```

The path segment is derived from the service id: lowercase, minus the
`-service` suffix. The derivation goes **one way only** — the gateway never
rebuilds a service id from a path segment.

- **Registering in Eureka does not expose a service.** Until its service id is
  in the allowlist, `/api/{name}/**` returns **404**. Deliberate friction.
- `api-gateway` and `eureka-server` in the allowlist are rejected at **startup**
  — routing infrastructure through the gateway is a loop.
- **The path is not rewritten.** The destination receives the full URL, prefix
  included.
- A service on the allowlist with **no UP instances returns 503 + `Retry-After`**,
  never 404. The route exists even when the instance does not.
- There is no `/actuator/gateway/routes` endpoint (it is not registered in this
  Spring Cloud version). Ask the door instead — see §9.

### Route conventions

| Path | Token |
|---|---|
| `/api/{name}/public/**` | none — anonymous |
| `/api/{name}/**` | person token (cookie) or service token (header) |
| `/.well-known/jwks.json` | none — static route to `lb://users-service` |
| `/api/docs/**` | none — the Swagger screen for the whole subsystem |
| `/fallback/**` | none — internal circuit-breaker destination |
| `/actuator/health/**`, `/actuator/prometheus` | none |

---

## 4. Getting your service registered and routed

Six steps, in order. Nothing here needs a change in the gateway's code.

**1 · Name.** `spring.application.name = {your}-service`. The same string is
your Eureka service id, your allowlist entry, your `aud` in a service token,
your `X-Service-Id`, and the source of your public path. Pick it once.

**2 · Register in Eureka.**

```yaml
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://eureka:8761/eureka/}
    register-with-eureka: true
    fetch-registry: false        # only the gateway needs true
    healthcheck:
      enabled: true              # publishes readiness, not just the heartbeat
  instance:
    prefer-ip-address: true
```

`healthcheck.enabled: true` is what keeps a half-started instance out of the
load balancer. Without it Eureka marks UP on the heartbeat alone, which says
"the process is alive", not "it can serve".

**3 · Network.** `expose:`, never `ports:`, on the external `tpi-platform`
network. Running from the IDE instead, Eureka is at `http://localhost:8761/eureka/`.

**4 · Readiness.** `GET /actuator/health/readiness` must answer on the
management port.

**5 · Ask for the allowlist entry**, with the exact service id. In practice this
is one environment variable on the gateway's container:

```
GATEWAY_ALLOWLIST=users-service,echo-service,cursos-service
```

It replaces the whole list and requires a gateway restart — never hot.

**6 · (Optional) Add your spec to the docs dropdown**, one line in
`springdoc.swagger-ui.urls` pointing at `/api/{name}/public/v3/api-docs`. Being
in the dropdown does **not** expose the API; that is still the allowlist.

---

## 5. Headers the gateway injects

### The five reserved headers

`X-Principal-Type`, `X-User-Id`, `X-User-Roles`, `X-Service-Id`,
`X-Service-Scopes`.

`IdentityPropagationFilter` does two things, in this order, and the order is the
contract:

1. **Strips all five**, wherever they came from, on **every** request — public
   routes included.
2. **Injects** the set derived only from the validated `Jwt`. Never a value
   copied from the incoming request.

| Header | Person token (`type: user`) | Service token (`type: service`) |
|---|---|---|
| `X-Principal-Type` | `user` | `service` |
| `X-User-Id` | the `sub` (UUID) | — |
| `X-User-Roles` | `roles`, comma-separated, no spaces | — |
| `X-Service-Id` | — | the `sub` (client id) |
| `X-Service-Scopes` | — | roles first (`MS`), then scopes, comma-separated |

Format (`DEC-05`): comma with no space, no empty values, no duplicates, no
trailing comma, stable order.

On an anonymous request (a public route with no token) the five are stripped and
**none** is injected. A destination that sees no `X-Principal-Type` did not
receive an identity — treat it as anonymous, or reject with
`401 not-authenticated` if the route needs one.

### Trace headers

| Header | Behaviour |
|---|---|
| `X-Request-Id` | Preserved if it matches `[A-Za-z0-9._:-]{1,128}`, otherwise regenerated as a UUID. Echoed back in the **response**. |
| `traceparent` | Preserved if it is strict W3C (`00-{32 hex}-{16 hex}-0[01]`), otherwise generated. **Not** stripped — overwriting it would split the trace at the edge. |

Both are untrusted input: anything malformed is discarded and regenerated, which
is what stops log-line injection. The `traceId` that travels downstream is the
one inside `traceparent`, and it is the one in the MDC.

### `Authorization`

Forwarded as is; the gateway does not touch it. Since the cookie cutover, a
person token never populates it anyway — only a service token does.

### `on_behalf_of`

Stays in the gateway's log (`ON_BEHALF_OF service=… actor=… path=…`). **No
header is created for it.** That log line is the only record.

---

## 6. What the gateway expects in a token

Keys come from the JWKS of users-service (`JWKS_URI`), RS256.

**Always validated**, in the decoder, with no flag: signature, `exp`/`nbf`, and
`iss == gateway.jwt.expected-issuer` (`users-service`).

**Person token** — must arrive in the `fu_at` cookie. Arriving in the
`Authorization` header it is rejected (`person-via-header`).

| Claim | Requirement |
|---|---|
| `type` | `user` |
| `sub` | non-blank |
| `roles` | non-empty |
| `sid` | required — checked against `session:{sub}` in Redis |
| `est` | required — `ACTIVE`, or the request is gated |
| `pwd` | required boolean — `true` gates the request |
| `onb` | required boolean — `true` gates the request |

`est`, `pwd` and `onb` are mandatory (`DEC-44`): a missing one is a 401, and the
log names which one.

**Service token** — must arrive in the `Authorization: Bearer` header. Arriving
in a cookie it is rejected (`service-via-cookie`).

| Claim | Requirement |
|---|---|
| `type` | `service` |
| `sub` | non-blank — the client id |
| `roles` | must contain `MS` |
| `aud` | must contain the resolved destination service id, or 403 `invalid-audience` |
| `scope` | space- or comma-separated; forwarded in `X-Service-Scopes` |

### What the gateway does NOT do

- **It does not authorize by role.** Zero `hasRole`/`hasAuthority`: everything
  private is `authenticated()` and nothing more. `@PreAuthorize` lives in your
  service.
- It does not rewrite paths, does not validate scopes against a catalog, and
  does not parse your business payloads.

The account-state gate is not an exception to the role rule: account state is
"whether you can act", not "who you are allowed to be". An account that is not
enabled can only reach `gateway.account-gate.exempt-prefixes` and
`/api/*/public/**` — so if a request reaches your service, that person is
enabled and you never have to handle account states.

---

## 7. Error contract

Every rejection the gateway writes goes through `ProblemDetails` — RFC 9457
`application/problem+json`, one shape for the whole platform. A filter writing
its own body is a bug.

```jsonc
{
  "type": "https://tpi.utn.frc/errors/session-superseded",
  "title": "Session superseded",
  "status": 401,
  "detail": "Another device signed in with this account.",
  "instance": "/api/users/me",      // the path the CLIENT asked for
  "requestId": "..."                // when present
}
```

| `type` | HTTP | Raised by | Meaning |
|---|---|---|---|
| `not-authenticated` | 401 | Security / `PrivateRouteGuard` / `AccountStateGuard` | No valid identity, or a malformed token. |
| `session-closed` | 401 | `SessionGuard` | No session in Redis for that user. |
| `session-superseded` | 401 | `SessionGuard` | A newer login exists. |
| `pending-account` | 403 | `AccountStateGuard` | Account not `ACTIVE`; carries `accountStatus`. |
| `password-change-required` | 403 | `AccountStateGuard` | `pwd: true`. |
| `onboarding-pending` | 403 | `AccountStateGuard` | `onb: true`. |
| `invalid-audience` | 403 | `ServiceAudienceFilter` | Service token used against another destination. |
| `access-denied` | 403 | error handler | |
| `route-not-found` | 404 | error handler / fallback | Path outside the allowlist, or a direct call to `/fallback/**`. |
| `method-not-allowed` | 405 | error handler | |
| `too-many-attempts` | 429 | `RateLimitFilter` | Carries `Retry-After`. |
| `unexpected-error` | 500 | `GatewayErrorHandler` | Anything that escapes the pipeline. The stack trace goes to the log, never to the body. |
| `service-unavailable` | 503 | `SessionGuard` / `BulkheadFilter` / fallback | Redis, saturation or a down destination. Carries `Retry-After` — **retry, never send to login**. |

Four things worth knowing:

- An exception's own message is **never** copied into the body. Only the `type`
  and a fixed, sanitized `detail`.
- **A client that hung up produces no response and no `ERROR`.** A browser
  navigating away mid-request or a dropped mobile connection is routine under
  load, not a gateway failure: it is logged at `DEBUG` as `CLIENT_DISCONNECTED`
  and nothing is written, because there is nobody left to answer. The check is
  the framework's own (`DisconnectedClientHelper`), the same one WebFlux uses
  for its `DisconnectedClient` category. So an `UNEXPECTED_ERROR` in the log
  always means a real failure — that is what makes it worth alerting on.
- `instance` is the path the client asked for, not the one being served. When
  the breaker forwards to `/fallback/servicio`, the client still sees its own
  path.
- A nonexistent route gives `404 route-not-found` **only with a valid token**.
  Without one it is `401`, because the Security chain runs before routing and
  does not yet know the route does not exist.

---

## 8. Configuration reference

Everything below is read at startup. Nothing is hot-reloadable.

### Required

| Variable | Notes |
|---|---|
| `JWKS_URI` | **No default, on purpose.** Missing → the service does not start and names the placeholder. A default would boot the gateway pointing at the wrong JWKS and answer 401 to everything. |

### Routing and identity

| Variable | Property | Default |
|---|---|---|
| `GATEWAY_ALLOWLIST` | `gateway.routing.allowlist` | `users-service,echo-service` |
| — | `gateway.routing.service-id-suffix` | `-service` |
| — | `gateway.routing.path-prefix` | `/api` |
| — | `gateway.jwt.expected-issuer` | `users-service` |
| `GATEWAY_ACCOUNT_EXEMPT` | `gateway.account-gate.exempt-prefixes` | `/api/users/**` |

`gateway.identity.reserved-headers` lists the five stripped headers. Do not
invent new names — the list is the contract.

### Session

| Variable | Property | Default |
|---|---|---|
| `REDIS_HOST` / `REDIS_PORT` | `spring.data.redis.*` | `localhost` / `6379` |
| — | `spring.data.redis.timeout` | `500ms` |
| — | `gateway.session-cache.ttl` | `3s` |
| — | `gateway.session-cache.max-size` | `10000` |

The Redis timeout is not cosmetic: with Lettuce's 60s default, a Redis that goes
mute instead of refusing connections hangs every authenticated request for a
minute instead of answering 503 in half a second.

### Rate limit

| Variable | Default |
|---|---|
| `RATE_LIMIT_AUTH_CAPACITY` / `RATE_LIMIT_AUTH_REFILL` | `600` / `600` per minute, keyed by IP, on `/api/users/public/auth/**` |
| `RATE_LIMIT_REGISTRATION_CAPACITY` / `RATE_LIMIT_REGISTRATION_REFILL` | `10` / `10` per minute, keyed by IP, on `/api/users/public/registration/**` |
| `GATEWAY_TRUSTED_PROXIES` | `172.16.0.0/12,127.0.0.1/32` |

Only the routes listed in `expensive-routes` are limited; everything else is a
no-op. A route configured with a threshold of `0` **fails at startup**.

`trusted-proxies` cannot be empty behind nginx: without it the gateway ignores
`X-Forwarded-For` and buckets everyone under nginx's IP — one script hammering
`/auth` would lock out every login. The match is a real bit-level CIDR
comparison, so `/12`, `/22` and IPv6 all behave.

### Resilience

| Variable | Property | Default |
|---|---|---|
| `BULKHEAD_ENABLED` | `gateway.bulkhead.enabled` | `true` |
| `BULKHEAD_MAX_CONCURRENT` | `gateway.bulkhead.max-concurrent-calls` | `64` per destination |
| — | `gateway.bulkhead.max-wait` | `0ms` (reject immediately) |
| `GATEWAY_BREAKER_WINDOW` | sliding window size | `20` |
| `GATEWAY_BREAKER_THRESHOLD` | failure rate % | `50` |
| `GATEWAY_BREAKER_WAIT` | wait in open state | `10s` |
| `GATEWAY_BREAKER_HALF_OPEN` | calls in half-open | `3` |
| `GATEWAY_TIMEOUT` | time limiter | `3s` |
| `GATEWAY_BULKHEAD_RETRY_AFTER` | `Retry-After` on 503 | `5s` |
| `GATEWAY_FALLBACK_RETRY_AFTER` | `Retry-After` on 503 | `10s` |

Layer order: rate limit → bulkhead → timeout → retry → circuit breaker →
fallback. Each covers a different failure. The breaker needs
`statusCodes: 500,502,503,504`: without it a backend 500 is not an exception in
the reactive chain and the breaker never opens. Retry is **GET only** — retrying
a `POST /registration` duplicates a signup.

### Infrastructure

| Variable | Default |
|---|---|
| `EUREKA_URL` | `http://localhost:8761/eureka/` |
| `SERVER_PORT` | `8080` |
| `MANAGEMENT_PORT` | `8081` |
| `OPENAPI_ENABLED` | `true` |

Values in `application.yml` must stay **ASCII**: a non-ASCII value comes back
mojibake even though the file is valid UTF-8. The same character in a `.java` is
fine — the YAML loader is what fails to decode it.

---

## 9. Observability and diagnosis

- **Log pattern:** `%5p [appName,traceId,spanId,requestId]`, on every line, with
  no filter logging the ids by hand. `spring.reactor.context-propagation: auto`
  is what carries them across Reactor's thread hops.
- **Sampling is 1.0.** At the default 10% nine out of ten requests come out with
  no `traceId`.
- **Never logged:** bodies, tokens, `Authorization`, client secrets.
- **Dev mailbox:** `InterMicroTraceFilter` pushes the last 200 routed calls into
  the Redis list `intermicro:trace` (origin `PERSON`/`MS`/`ANON`, actor,
  destination, status, ms). Development only, fire-and-forget.
- **Metrics:** `/actuator/prometheus` on the management port.
- **API reference for the whole subsystem:** `http://localhost:3000/api/docs/ui`.

### Is my service routed?

Ask the door, with the stack up:

```bash
curl -s http://localhost:8761/eureka/apps | grep -o '<name>[^<]*</name>'
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/api/cursos/anything
#   404 -> not on the allowlist
#   503 -> on the allowlist, no UP instances
#   401 -> routed (no token, which is expected)
```

---

## 10. Two windows worth knowing about

**Session state is cached for 3 seconds.** A revoked token survives that long,
and a freshly issued one can be rejected inside the same window. The cache
exists because the gateway is fail-closed: without it, a brief Redis hiccup
takes the whole platform down. The "unavailable" state is deliberately **not**
cached — caching a Redis failure turns a hiccup into a guaranteed three-second
outage.

**Redis failure is 503, not 401.** Telling somebody whose session is perfectly
fine that it expired, because Redis went down, sends them to re-login for
nothing. Absent key and unreachable Redis are different states and stay
different all the way to the response.

