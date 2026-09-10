# Task Assignment · Topic 01 · Identity and Users

Two services, eleven people, no merge conflicts. This document says who owns
what, in which order, and how to start.

## How the work is split

Lots are built around **file ownership**, not around task numbers. Two tasks that
touch the same file always belong to the same lot, so **no two people ever edit
the same file**. That is the single rule that keeps ten parallel branches from
turning into a week of merge conflicts.

Every lot is a set of tasks from the implementation plan
(`docs/plans/`). Each task is written test-first: step one is always the failing
test, and the task is done when it passes and the acceptance check in its last
step returns what the plan says it should.

| Lot | Owner | Service | Plan tasks | Lines | Scope |
|---|---|---|---|---|---|
| Base | Ibazeta, Ramiro Gonzalo | both | U1–U5, U12, G1–G2 | 2 952 | Scaffolding, schema, error contract, gateway identity, module boundary |
| L1 | Del Lungo, Mateo | users | U13, U14, U15 | 1 093 | Two-phase login, 2FA, refresh, single session, password change and reset |
| L2 | Baldassari, Agustín | users | U9, U10, U16 | 897 | RS256 keys, JWKS, token issuance, `client_credentials` with `audience` |
| L3 | Cabrera, Manuel | users | U6, U7, U11 | 927 | Redis stores, OTP, transactional outbox, e-mail templates |
| L4 | Carignano Valenzuela, Maximiliano | users | U8, U21, U23 | 842 | Account gates, Kafka consumer, module boundary test, Dockerfile |
| L5 | Palacios, Santiago | users | U17, U20 | 849 | Registration, account activation by link, professor whitelist |
| L6 | Jatuf, Thiago Uriel | users | U18, U19, U22 | 961 | Profile, onboarding, admin operations, initial admin |
| L7 | Cobos Robert, Lorenzo | gateway | G3, G4, G5 | 667 | Allowlist routing, error contract, session repository |
| L8 | Sanchez German, Manuel | gateway | G6, G7 | 685 | Security chain, issuer and session validation, traceability |
| L9 | Tahir, Martina Nicole | gateway | G8, G9, G10, G11 | 941 | Route guards, account-state guard, audience, identity propagation |
| L10 | Zambrano, Joaquin | gateway | G12, G13, G14, G15 | 872 | Rate limiting, pipeline order, resilience, packaging |

Average lot: 923 lines. Spread: 667–1 093.

> **Line count is not effort.** L10 is one of the smaller lots and one of the
> hardest: `PipelineOrderIT` verifies the *real* order of the filter chain,
> which is where the bugs no unit test can see actually live. L1 is the largest
> and the most conceptually dense. Swap lots on day one if the fit is wrong —
> after that, don't.

## What "Base" means

The base is pushed before anyone starts. It contains the project skeleton, the
database schema and entities, the uniform error contract, the gateway identity
filter and the `CredentialService` boundary between `auth/` and `users/`.

Everything below depends on it, and **nothing in it is edited by anyone else**.
If a lot needs a change in the base, it is requested, not committed.

## Order of work

Not everything can start at once. Dependencies:

**Wave 1 — start immediately, depend only on the base**

| Lot | Owner |
|---|---|
| L2 | Baldassari |
| L3 | Cabrera |
| L4 | Carignano |
| L7 | Cobos Robert |

**Wave 2 — needs Wave 1**

| Lot | Owner | Waits for |
|---|---|---|
| L1 | Del Lungo | L2 (token issuance), L3 (Redis, OTP, mail) |
| L5 | Palacios | L3 (mail, ephemeral tokens) |
| L8 | Sanchez German | L7 — sólo para correr sus dos IT: necesita el **bean** que implementa `SessionRepository` y el ruteo. Compila desde el día uno |
| L9 | Tahir | L7 — sólo para las IT: necesita el ruteo por allowlist. Compila desde el día uno |

**Wave 3 — needs Wave 2**

| Lot | Owner | Waits for |
|---|---|---|
| L6 | Jatuf | L4 (account gates) |
| L10 | Zambrano | L8, L9 (the filters whose order it verifies) |

**Everyone starts on day one — but not every lot compiles on day one.** Step one
of every task is the failing test, and for most lots that test compiles against
the base from the first commit. L8 and L9 are the clean case, which is why the
table above says so.

Two lots are not that case, and the reason is always a test double that extends
or decorates a class somebody else owns:

- **L5** cannot compile at all without L3, tests included: `TestActivationSpy
  extends NotificationEventPublisher`, and that class is L3's.
- **L1** is split. `TestOtpSpy` implements `SecondFactorProvider`, which L1 owns
  itself, so U13 and U14 compile from day one. `TestResetSpy` decorates
  `NotificationEventPublisher`, so U15 does not.

Do not wait for the merge. Bring the dependency into your branch locally:

```
git fetch origin
git merge origin/lote-3-cabrera
```

That merge is **local and disposable**, and it never goes into your pull request:
when the lot you depend on lands in `main`, you rebase onto `main` and it
disappears. If the branch you merged still has changes requested, its public
signatures can still move — write against the **plan**, which is the contract,
not against whatever that branch happens to say today.

## Rules

**One branch per lot.** Name it `lote-N-apellido`. Small, frequent pull requests
beat one large one at the end.

**Never edit a file you don't own.** The file list in your assignment is
exhaustive. If you need something changed outside it, ask the owner — that is a
thirty-second conversation and a fifteen-minute merge conflict avoided.

**That includes when the plan itself tells you to.** A step that says to edit a
file outside your list is a bug in the plan, not permission. Stop and report it:
the answer is either that the file moves to the base, or that your lot gets its
own copy — and either way it is a change to the assignment, not something to
settle inside one branch.

**The same goes for a file you need but nobody owns, or that another lot owns
and has not written yet.** This has happened three times now — `ProblemDetails`
and `SessionRepository`, the mail spies, `SkipAccountGate` — and the answer was
the same every time: **anything two or more lots need in order to COMPILE
belongs to the base.** The implementation stays with its lot; the seam does not.
Report it and it gets moved, usually the same day. Do not wait for the other
lot to merge, and do not write your own copy of their file.

**Three files belong to Ramiro alone:** `application.yml`, `pom.xml` and the
`Dockerfile`. Everyone adds properties and dependencies eventually, and these
are where ten branches collide. Send the block you need and it gets merged for
everyone. (`Dockerfile` is the deliverable of L4 and L10 — after that it is
frozen.)

**Green before merge.** `mvn -q clean verify` passes - `clean` and `verify`, and
both words earn their place. Without `verify` the `*IT` classes never run:
failsafe binds them to `verify` and `mvn test` skips every one of them silently.
Without `clean` the build can pass on stale classes: when a shared file changes,
Maven recompiles only that file and leaves the `.class` of everything that
referenced it, so you get green against a classpath that no longer exists. That
is how a `main` that does not compile got merged once already. And the acceptance check in the last step of
each task returns what the plan says.

**Daily sync, fifteen minutes, fixed format:** what I finished, what I'm on, what
blocks me. Blockers go to the group chat the moment they appear, not at the next
sync.

## Contract changes

A DTO, an error `type` or an interface signature that another lot consumes is a
**shared contract**. Changing one is not a private decision: announce it before
committing, because someone else has already written tests against it.

---

# Individual assignments

Each section below is self-contained. Copy the prompt into your agent from the
repository root, with the plugin installed (see `docs/AGENT-SETUP.md`).

---

## Baldassari, Agustín — L2 · Token issuance

**Repository:** `users` · **Tasks:** U9, U10, U16 · **Wave 1**

**Files you own**

```
scripts/gen-dev-keys.sh
src/main/java/…/auth/keys/SigningKeyProvider.java
src/main/java/…/auth/keys/impl/FileSystemSigningKeyProvider.java
src/main/java/…/auth/controllers/JwksController.java
src/main/java/…/auth/controllers/TokenController.java
src/main/java/…/auth/tokens/TokenClaims.java
src/main/java/…/auth/services/TokenService.java
src/main/java/…/auth/services/ServiceClientService.java
src/main/java/…/auth/ScopeCatalog.java
src/main/java/…/auth/dto/ClientCredentialsRequest.java
src/main/java/…/config/JwtProperties.java
src/test/java/…/auth/keys/SigningKeyProviderTest.java
src/test/java/…/auth/tokens/TokenContractTest.java
src/test/java/…/auth/ClientCredentialsIT.java
```

**Why your lot matters:** every token in the platform is signed by your code,
and the `audience` claim you implement is what stops a leaked client secret from
becoming a key to the whole platform.

**Prompt**

```
Implementá el lote L2 del plan docs/plans/users-service.md: las tareas U9, U10 y U16.

Trabajá en la rama lote-2-baldassari. Seguí el plan tarea por tarea y paso por
paso, sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista: si necesitás un cambio en otro lado, pará y
decilo.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida antes de seguir con
la siguiente.
```

---

## Cabrera, Manuel — L3 · Redis, events and mail

**Repository:** `users` · **Tasks:** U6, U7, U11 · **Wave 1**

**Files you own**

```
src/main/java/…/shared/events/EventEnvelope.java
src/main/java/…/shared/events/AccountEventPublisher.java
src/main/java/…/shared/events/OutboxPoller.java
src/main/java/…/shared/notifications/EmailType.java
src/main/java/…/shared/notifications/EmailTemplateService.java
src/main/java/…/shared/notifications/NotificationEventPublisher.java
src/main/java/…/auth/store/TokenStore.java
src/main/java/…/auth/store/EphemeralTokenService.java
src/main/java/…/auth/store/impl/RedisTokenStore.java
src/main/java/…/auth/store/impl/RedisEphemeralTokenService.java
src/main/java/…/auth/otp/OtpService.java
src/main/java/…/config/KafkaTopicsProperties.java
src/main/java/…/config/OtpProperties.java
src/main/resources/templates/*.html
src/main/resources/messages.properties
src/test/java/…/shared/events/OutboxIT.java
src/test/java/…/shared/notifications/EmailTypeTest.java
src/test/java/…/auth/otp/OtpServiceIT.java
```

**Why your lot matters:** three other lots consume what you build. It is the
first thing that has to land, and the outbox pattern you implement is what keeps
a broker outage from taking every database write down with it.

**Prompt**

```
Implementá el lote L3 del plan docs/plans/users-service.md: las tareas U6, U7 y U11.

Trabajá en la rama lote-3-cabrera. Seguí el plan tarea por tarea y paso por
paso, sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Tres lotes más dependen de lo que construyas, así que las firmas públicas de
TokenStore, EphemeralTokenService, OtpService y NotificationEventPublisher son
contrato: si algo te obliga a cambiarlas respecto del plan, pará y avisá antes
de tocarlas.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Carignano Valenzuela, Maximiliano — L4 · Gates, consumer and boundary

**Repository:** `users` · **Tasks:** U8, U21, U23 · **Wave 1**

**Files you own**

```
src/main/java/…/shared/gates/AccountGateInterceptor.java
src/main/java/…/users/listeners/CourseValidationListener.java
src/main/java/…/config/WebConfig.java
src/main/java/…/config/KafkaConfig.java
src/test/java/…/shared/gates/AccountGateInterceptorTest.java
src/test/java/…/users/CourseValidationListenerIT.java
src/test/java/…/TimestampIT.java
src/test/java/…/ArchitectureTest.java
Dockerfile
.dockerignore
```

**Why your lot matters:** the three account gates decide what an
unverified account can and cannot reach. Read the exemption rule in task U8
before writing a line — it is the part everyone gets wrong, and getting it wrong
locks the initial administrator out of the platform permanently.

**Prompt**

```
Implementá el lote L4 del plan docs/plans/users-service.md: las tareas U8, U21 y U23.

Trabajá en la rama lote-4-carignano. Seguí el plan tarea por tarea y paso por
paso, sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Antes de escribir código, leé completo el bloque de la regla de las exenciones
en la tarea U8 y explicame con tus palabras por qué un endpoint de salida se
exime de los dos gates finos y no solo del propio.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Cobos Robert, Lorenzo — L7 · Routing and error contract

**Repository:** `api-gateway` · **Tasks:** G3, G4, G5 · **Wave 1**

**Files you own**

```
src/main/java/…/config/AllowlistRouteLocator.java
src/main/java/…/config/DiscoveryLocatorConfig.java
src/main/java/…/config/RedisConfig.java
src/main/java/…/web/GatewayErrorAttributes.java
src/main/java/…/web/FallbackController.java
src/main/java/…/web/RouteNotFoundHandler.java
src/main/java/…/repository/impl/RedisSessionRepository.java
src/main/java/…/repository/impl/CachingSessionRepository.java
src/test/java/…/integration/DiscoveryAllowlistIT.java
src/test/java/…/repository/CachingSessionRepositoryTest.java
```

**Why your lot matters:** `ProblemDetails` and the `SessionRepository` interface
live in the base so nobody is blocked on compiling, but L8 and L9 cannot RUN a
single integration test until your routing and your Redis beans are on `main`.
Yours is what turns their code from written to verifiable. Task G3 contains a trap
that produces a gateway which starts perfectly and answers 404 to everything —
read it before you touch the routing.

**Prompt**

```
Implementá el lote L7 del plan docs/plans/api-gateway.md: las tareas G3, G4 y G5.

Trabajá en la rama lote-7-cobos. Seguí el plan tarea por tarea y paso por paso,
sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Antes de empezar, leé el bloque sobre por qué las rutas dinámicas se generan en
Java y no con el DiscoveryClient locator, y explicame por qué ese error no
rompería el arranque.

Los lotes L8 y L9 dependen de tus beans: sus firmas
son contrato, avisá antes de cambiarlas.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Del Lungo, Mateo — L1 · Authentication flows

**Repository:** `users` · **Tasks:** U13, U14, U15 · **Wave 2** (needs L2 and L3)

**Files you own**

```
src/main/java/…/auth/services/AuthService.java
src/main/java/…/auth/services/PasswordService.java
src/main/java/…/auth/controllers/AuthController.java
src/main/java/…/auth/controllers/AuthPrivateController.java
src/main/java/…/auth/twofactor/SecondFactorProvider.java
src/main/java/…/auth/twofactor/EmailOtpProvider.java
src/main/java/…/auth/dto/LoginRequest.java
src/main/java/…/auth/dto/LoginResponse.java
src/main/java/…/auth/dto/VerifyTwoFactorRequest.java
src/main/java/…/auth/dto/TokenResponse.java
src/main/java/…/auth/dto/PasswordChangeRequest.java
src/main/java/…/auth/dto/ResetRequest.java
src/main/java/…/auth/dto/ResetConfirmRequest.java
src/main/java/…/config/RateLimitProperties.java
src/test/java/…/auth/LoginIT.java
src/test/java/…/auth/RateLimitLoginIT.java
src/test/java/…/auth/SingleSessionRefreshIT.java
src/test/java/…/auth/LogoutIT.java
src/test/java/…/auth/PasswordResetIT.java
src/test/java/…/auth/TestOtpSpy.java
src/test/java/…/auth/TestResetSpy.java
```

**Why your lot matters:** it is the largest and the densest. Single session,
refresh rotation and reuse detection all live here, and every one of them is a
place where a subtle mistake becomes a security hole rather than a bug.

**Prompt**

```
Implementá el lote L1 del plan docs/plans/users-service.md: las tareas U13, U14 y U15.

Trabajá en la rama lote-1-dellungo. Seguí el plan tarea por tarea y paso por
paso, sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Tu lote consume TokenService (L2, ya está en main) y TokenStore, OtpService y
NotificationEventPublisher (L3, todavía no). U13 y U14 los escribís sin esperar a
nadie: TestOtpSpy implementa SecondFactorProvider, que es un archivo tuyo. U15 no
compila sin L3, porque TestResetSpy decora NotificationEventPublisher. Para esa
tarea mergeá origin/lote-3-cabrera en tu rama, en local: es un merge descartable
y no se pushea — cuando L3 entre a main, rebaseás contra main y se va. Escribí
contra los nombres que dice el plan, no contra los que tenga esa rama hoy.

Prestá atención a los tipos de error del refresh: un refresh que ya no sirve no
es una credencial equivocada, es una sesión que dejó de existir, y el mensaje
que ve la persona tiene que decir eso.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Palacios, Santiago — L5 · Registration and whitelist

**Repository:** `users` · **Tasks:** U17, U20 · **Wave 2** (needs L3)

**Files you own**

```
src/main/java/…/users/services/RegistrationService.java
src/main/java/…/users/services/WhitelistService.java
src/main/java/…/users/controllers/RegistrationController.java
src/main/java/…/users/controllers/WhitelistController.java
src/main/java/…/users/controllers/LegalController.java
src/main/java/…/users/dto/StudentRegistrationRequest.java
src/main/java/…/users/dto/ProfessorRegistrationRequest.java
src/main/java/…/users/dto/ActivateAccountRequest.java
src/main/java/…/users/dto/ResendCodeRequest.java
src/main/java/…/users/dto/AddEmailRequest.java
src/main/java/…/users/dto/CreateWhitelistRequest.java
src/main/java/…/users/dto/ResolveWhitelistRequest.java
src/main/resources/legal/terms-v1.md
src/test/java/…/users/RegistrationIT.java
src/test/java/…/users/ActivationLinkIT.java
src/test/java/…/users/WhitelistRequestIT.java
src/test/java/…/users/TestActivationSpy.java
```

**Why your lot matters:** it is the front door of the platform. Task U17 carries
three rules about the activation link that are the difference between a flow
that works and one that fails for every institutional mailbox — read them before
implementing.

**Prompt**

```
Implementá el lote L5 del plan docs/plans/users-service.md: las tareas U17 y U20.

Trabajá en la rama lote-5-palacios. Seguí el plan tarea por tarea y paso por
paso, sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Antes de escribir el servicio de registro, leé las tres decisiones del paso 4 de
la tarea U17 y explicame por qué el enlace apunta al frontend y la activación es
un POST.

Tu lote consume EphemeralTokenService y NotificationEventPublisher (L3), y hasta
que L3 no esté en main no te compila nada, ni siquiera los tests: TestActivationSpy
extiende NotificationEventPublisher. Mergeá origin/lote-3-cabrera en tu rama, en
local: es un merge descartable y no se pushea — cuando L3 entre a main, rebaseás
contra main y se va. Escribí contra los nombres que dice el plan, no contra los
que tenga esa rama hoy.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Sanchez German, Manuel — L8 · Security and traceability

**Repository:** `api-gateway` · **Tasks:** G6, G7 · **Wave 2** (needs L7)

**Files you own**

```
src/main/java/…/config/SecurityConfig.java
src/main/java/…/security/IssuerValidator.java
src/main/java/…/security/SessionValidator.java
src/main/java/…/filters/SessionGuard.java
src/main/java/…/filters/CorrelationIdFilter.java
src/main/java/…/filters/LoggingFilter.java
src/test/java/…/integration/IssuerValidationIT.java
src/test/java/…/integration/SessionInvalidationIT.java
src/test/java/…/filters/CorrelationIdFilterTest.java
src/test/java/…/filters/LoggingFilterTest.java
```

**Why your lot matters:** traceability is a platform-wide contract, not a
gateway feature. If the correlation id does not reach the log line, no one can
follow a request across services — and every other team inherits the problem.

**Prompt**

```
Implementá el lote L8 del plan docs/plans/api-gateway.md: las tareas G6 y G7.

Trabajá en la rama lote-8-sanchez. Seguí el plan tarea por tarea y paso por
paso, sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Al terminar G7, verificá a mano que funciona de punta a punta: mandá un request
con un X-Request-Id conocido y comprobá que ese id aparece en la línea de log
del Gateway. Que el pattern lo declare no alcanza: hay que verlo impreso.

ProblemDetails y SessionRepository ya estan en la base: compilas desde el dia
uno. De L7 esperas los BEANS (ruteo y Redis) para que tus dos IT levanten.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Tahir, Martina Nicole — L9 · Route guards and identity propagation

**Repository:** `api-gateway` · **Tasks:** G8, G9, G10, G11 · **Wave 2** (needs L7)

**Files you own**

```
src/main/java/…/filters/PublicRouteGuard.java
src/main/java/…/filters/PrivateRouteGuard.java
src/main/java/…/filters/AccountStateGuard.java
src/main/java/…/filters/ServiceAudienceFilter.java
src/main/java/…/filters/IdentityPropagationFilter.java
src/test/java/…/filters/PrivateRouteGuardTest.java
src/test/java/…/integration/PublicPrivateRouteIT.java
src/test/java/…/integration/AccountStateGuardIT.java
src/test/java/…/integration/ServiceAudienceIT.java
src/test/java/…/integration/IdentityPropagationIT.java
```

**Why your lot matters:** `IdentityPropagationFilter` is the anti-spoofing
control of the entire platform. It strips the five reserved headers before
injecting its own — including on public routes, because a public route where a
caller can declare itself an administrator is the largest hole there is.

**Prompt**

```
Implementá el lote L9 del plan docs/plans/api-gateway.md: las tareas G8, G9, G10 y G11.

Trabajá en la rama lote-9-tahir. Seguí el plan tarea por tarea y paso por paso,
sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

En G11, el borrado de los cinco headers reservados tiene que aplicar también en
las rutas públicas, y el test lo tiene que demostrar: es el caso que parece
innecesario y es el más importante.

ProblemDetails ya esta en la base. De L7 esperas el ruteo por allowlist para
que tus IT no den 404.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Jatuf, Thiago Uriel — L6 · Profile, admin operations and initial admin

**Repository:** `users` · **Tasks:** U18, U19, U22 · **Wave 3** (needs L4)

**Files you own**

```
src/main/java/…/users/services/UserService.java
src/main/java/…/users/controllers/UserController.java
src/main/java/…/users/dto/OnboardingRequest.java
src/main/java/…/users/dto/UserMeResponse.java
src/main/java/…/users/dto/ProfileResponse.java
src/main/java/…/users/dto/CreateUserRequest.java
src/main/java/…/users/dto/RoleChangeRequest.java
src/main/java/…/users/dto/AdminDeactivationRequest.java
src/main/java/…/users/cli/AdminBootstrap.java
src/main/java/…/auth/cli/AdminRecoveryCommand.java
src/test/java/…/users/OnboardingWithoutAvatarIT.java
src/test/java/…/users/ProfileIT.java
src/test/java/…/users/AdminRulesIT.java
src/test/java/…/users/AdminBootstrapTest.java
src/test/java/…/auth/AdminRecoveryCommandTest.java
```

**Note:** your lot is the only one that adds properties to `application.yml`
(the `users.bootstrap` block). Send the block to Ramiro instead of committing it.

**Why your lot matters:** `GET /me` is the single endpoint that decides which
screen the frontend shows, and your `AdminBootstrap` is what makes a clean
installation usable at all.

**Prompt**

```
Implementá el lote L6 del plan docs/plans/users-service.md: las tareas U18, U19 y U22.

Trabajá en la rama lote-6-jatuf. Seguí el plan tarea por tarea y paso por paso,
sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

Tu lote necesita los gates de cuenta (L4) para las exenciones de /me y
/me/onboarding. Si todavía no están, escribí igual los tests.

El bloque de properties `users.bootstrap` no lo commitees: pasáselo a Ramiro,
que es el dueño de application.yml.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Zambrano, Joaquin — L10 · Rate limiting, pipeline order and resilience

**Repository:** `api-gateway` · **Tasks:** G12, G13, G14, G15 · **Wave 3** (needs L8, L9)

**Files you own**

```
src/main/java/…/ratelimit/RateLimitKeyResolver.java
src/main/java/…/ratelimit/TokenBucket.java
src/main/java/…/ratelimit/impl/InMemoryTokenBucket.java
src/main/java/…/ratelimit/impl/PrincipalRateLimitKeyResolver.java
src/main/java/…/filters/RateLimitFilter.java
src/main/java/…/config/ResilienceConfig.java
src/test/java/…/integration/RateLimitForwardedIT.java
src/test/java/…/integration/PipelineOrderIT.java
src/test/java/…/integration/ResilienceIT.java
src/test/java/…/support/FilterSequence.java
Dockerfile
.dockerignore
```

**El bloque de `default-filters` de G14 no es tuyo.** El paso 2 de esa tarea te
hace agregar el filtro `CircuitBreaker` a `application.yml`, y ese archivo es de
Ramiro. Ya está commiteado en `main`, con el `statusCodes` que el snippet del
plan no declaraba: sin él, el filtro sólo reacciona a excepciones de la cadena
reactiva y un 500 del backend pasa de largo sin abrir el breaker.

**Why your lot matters:** `PipelineOrderIT` verifies the order the filters
*actually* run in, not the one they declare. A misplaced `@Order` is a guard
running after the one that depended on it, and no unit test can see that.

**Prompt**

```
Implementá el lote L10 del plan docs/plans/api-gateway.md: las tareas G12, G13, G14 y G15.

Trabajá en la rama lote-10-zambrano. Seguí el plan tarea por tarea y paso por
paso, sin adelantarte: cada tarea empieza por el test que falla y termina con su
verificación.

G13 verifica el orden real del pipeline, así que necesita los filtros de L8 y
L9 mergeados. Empezá por G12 y G14, que no dependen de ellos.

En G12, prestá atención a cómo se obtiene la IP real: un rate limit por IP que
se puede evadir con un header no limita nada.

Tus archivos son los que lista la asignación en TASK-ASSIGNMENT.md. No edites
ningún archivo fuera de esa lista.

Al terminar cada tarea corré `mvn -q clean verify` y mostrame la salida.
```

---

## Ibazeta, Ramiro Gonzalo — Base and integration

**Repositories:** both · **Tasks:** U1–U5, U12, G1–G2

Pushed before anyone starts. After that: owner of `application.yml`, `pom.xml`
and the deployment orchestration, reviewer of every pull request, and the one
who runs the system regression suite at each integration point.
