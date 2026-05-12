# Admin Panel

A minimalistic and modern web-based admin panel for managing one or more PaperMC Minecraft servers behind an optional Velocity proxy.

Active implementation roadmap (phases, steps, success criteria, tests): `@.claude/docs/PLAN.md`.

## Project facts

- Plugin name: Admin Panel
- Base package: `org.rainbowhunter.adminpanel`
- JVM target: Java 25 (target only — sources are Kotlin)
- Minimum Paper version: 26.1.2 (for `agent-paper`)

## Architecture

Three components; agents dial OUT to Core so Core has the only inbound port.

```
Browser ── HTTPS ──► Core (Docker)  ◄── WebSocket ── agents (Paper / Velocity plugins)
```

- **Core** — standalone Kotlin/Ktor service in a Docker container. Owns the database, auth, the agent message bus, and serves the SPA.
- **Agents** — JVM plugins on each Paper/Velocity instance. Dial out to Core over WebSocket; never accept inbound connections.
- **Frontend** — React SPA built to static files, baked into the Core container's classpath. No Node.js in production.
- **Shared protocol** — Kotlin module consumed by Core + both agents. Single source of truth for WS message types.

## Tech stack

- **Source languages** — Kotlin for all JVM code (Core, agents, shared); TypeScript for frontend.
- **Core** — Ktor (REST + WebSocket), Exposed + HikariCP, Flyway, SQLite v1 (MySQL later), Argon2id, Ktor Sessions, Logback.
- **Agents** — Paper 26.1.2+ for `agent-paper`; latest Velocity for `agent-velocity`. Ktor client for the outbound WS.
- **Shared** — `kotlinx.serialization` only; no Ktor/Exposed dependencies (avoid Paper plugin classloader conflicts).
- **Frontend** — Vite, React 19, Tailwind CSS, shadcn/ui, TanStack Query, TanStack Router, Zustand, native WebSocket API.

## Repo layout

Gradle multi-module:

- `shared/` — protocol envelopes + DTOs
- `core/` — Ktor server, Dockerfile
- `agent-paper/` — Paper plugin
- `agent-velocity/` — Velocity plugin
- `frontend/` — Vite SPA; `dist/` is copied into `core/src/main/resources/web/` at assemble time
- `docker/` — `docker-compose.yml`

Per-module subpackages: `.protocol`, `.core`, `.agent.paper`, `.agent.velocity`.

## Conventions

- **Agents are Kotlin, not Java.** Java 25 is the JVM target; sources are Kotlin.
- **SQL stays DB-agnostic.** No SQLite-only syntax — MySQL must be a driver swap. Migrations via Flyway from day 1.
- **`shared/` stays lean.** Only `kotlinx.serialization`; never pull in Ktor or Exposed (plugin classloader risk).
- **TLS** is terminated by an external reverse proxy. Core listens HTTP internally.

### Auth model

- `/ap register` in-game → Core issues a one-time registration URL → user sets password on the web → subsequent logins use username + password.
- The registration token is for **registration only**, never ongoing login.
- New users are granted access by an admin via `/ap grant <player>` in-game; the granted user then runs `/ap register`.
- Bootstrap: when zero users exist, any op running `/ap register` becomes the first admin automatically.

### Agent registration

- Single shared registration token in Core config; all agents use it as their `core.token`.
- Agents auto-register on first `Hello { serverId, agentType, displayName }`. Core needs no advance knowledge of agent IPs/ports.
- Per-agent tokens (revocable) are a later hardening step; out of v1.