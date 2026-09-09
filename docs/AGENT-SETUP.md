# Agent setup

Everyone on the team uses a coding agent, and not the same one. This page gets
all of them to the same starting point.

Two things need to be in place before you write a line of code:

1. **The house rules** — what this subsystem does not negotiate, and why.
2. **The commands** — bringing the stack up, running the regression suite,
   pulling a verification code out of the database.

The rules are the part that matters. The commands are convenience.

---

## What you get

| Piece | What it is | Works with |
|---|---|---|
| `AGENTS.md` | The house rules, at the root of each repository | every agent listed below |
| `tpi-identidad` | Plugin: `/levantar`, `/regresion`, `/codigo` + the rules as a skill | Claude Code |
| `tpi-integracion` | Plugin for teams from **other topics** integrating behind the gateway | Claude Code |
| `scripts/regresion.sh` | 64 end-to-end checks against the running system | any shell |

If your agent reads `AGENTS.md`, you already have the important half. The plugin
adds the slash commands on top.

---

## Claude Code

```
/plugin marketplace add ./harness
/plugin install tpi-identidad
```

Verify it took: type `/` and you should see `levantar`, `regresion` and
`codigo` in the list.

Teams from other topics install the other one:

```
/plugin install tpi-integracion
```

Nothing else to configure. The skill activates on its own when you touch
endpoints, filters, security, gates, events or deployment files.

---

## Cursor

Cursor reads `AGENTS.md` from the repository root automatically — that is the
house rules covered, with no setup.

For the commands, the plugin content is plain Markdown and works as Cursor
rules. Copy them once:

```bash
mkdir -p .cursor/rules
cp harness/plugins/tpi-identidad/skills/reglas-del-subsistema/SKILL.md \
   .cursor/rules/reglas-del-subsistema.md
cp harness/plugins/tpi-identidad/commands/*.md .cursor/rules/
```

The three commands then work by asking for them by name — "levantá el stack",
"corré la regresión" — instead of `/levantar`.

---

## opencode

Reads `AGENTS.md` from the repository root. Same as Cursor: the rules come for
free.

For the commands:

```bash
mkdir -p .opencode/command
cp harness/plugins/tpi-identidad/commands/levantar.md  .opencode/command/
cp harness/plugins/tpi-identidad/commands/regresion.md .opencode/command/
cp harness/plugins/tpi-identidad/commands/codigo.md    .opencode/command/
```

---

## Antigravity

Reads `AGENTS.md` from the repository root.

There is no plugin format to install, so the commands are used by pasting their
content when you need them. The three files are short and each one explains what
it does and what to watch out for:

```
harness/plugins/tpi-identidad/commands/levantar.md
harness/plugins/tpi-identidad/commands/regresion.md
harness/plugins/tpi-identidad/commands/codigo.md
```

---

## Any other agent

`AGENTS.md` at the repository root is the portable format and covers the rules.
Everything else in `harness/` is Markdown: readable by hand, pasteable into any
tool.

---

## Check that it worked

Ask your agent:

> ¿Por qué users-service no publica puertos en el compose?

If the answer explains that the microservice trusts the `X-*` headers **because
nobody can reach it without going through the gateway**, and that publishing the
port would let anyone act as an administrator with a single header, the rules are
loaded. If the answer is vague, the file is not being read — check that
`AGENTS.md` is at the repository root and start a fresh session.

---

## Your loop vs. the integration step

**Your loop is `mvn -q verify` inside your repository.** The integration tests
use Testcontainers: they start their own MySQL, Redis and Kafka and need nothing
else. No compose, no other service, no coordination. That is the loop you should
be running dozens of times a day, and it is the one your task's acceptance check
refers to.

**The full stack is the integration step.** The subsystem's
`docker-compose.yml` is orchestrated outside these repositories (`DEC-40`) and
brings up the gateway, both services, MySQL, Redis, Kafka and Eureka together.
That is what `scripts/regresion.sh` talks to, over port 8080.

If you do not have that workspace, **do not write a compose to get around it.**
The real one carries security rules an improvised one will not — starting with
the fact that no microservice publishes a port. Ask for the workspace instead.

| You want to... | You need |
|---|---|
| Run your task's tests | `mvn -q verify` in your repo |
| Check you did not break the contract | the integration workspace + `scripts/regresion.sh` |
| Read a verification code or an activation link | the stack up, then `/codigo` |

---

## While you work

**Before your first commit of the day**, bring the stack up and run the
regression suite. Sixty-four checks, about three minutes. A red result on
something you have not touched means someone else's merge broke it, and that is
much cheaper to find in the morning than at the end of the sprint.

**Never publish a microservice port to debug.** `users-service` does not
validate the JWT: it trusts the `X-*` headers precisely because the gateway is
the only way in. With the port published, this works — no password, no token, no
second factor:

```bash
curl -X DELETE http://localhost:8082/api/users/{id} -H "X-User-Roles: ADMIN"
```

Use `docker compose logs` and `docker compose exec` instead.

**There is no mail server.** Verification codes and activation links are queued
in the `outbox_events` table. That is what `/codigo` reads.
