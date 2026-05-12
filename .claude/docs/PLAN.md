# Admin Panel — Step-by-Step Implementation Plan

## Context

Greenfield project: a web-based admin panel for managing one or more PaperMC servers behind an optional Velocity proxy.
Plugin name "Admin Panel", package `org.rainbowhunter.adminpanel`.

This plan assumes a single developer, a Docker-deployed Core service, and JVM agents that dial out to Core. There is no
existing code to reuse — current repo contains stub files (`src/Main.java`, `papermc_admin_panel.iml`) which will be
removed in Phase 0.

## Locked decisions

- **Architecture**: Core (Docker container) + Paper agent + Velocity agent + SPA frontend. Agents dial **out** to Core
  over WebSocket. Browser → Core is the only inbound direction.
- **Core**: Kotlin on Java 25; Ktor (REST + WebSocket); Exposed + HikariCP; Flyway migrations; SQLite v1, MySQL later;
  Argon2id; Ktor Sessions; Logback.
- **Agents**: Kotlin on Java 25; Paper 26.1.2+ for `agent-paper`; latest Velocity for `agent-velocity`.
- **Shared protocol**: Kotlin module depended on by Core + both agents (kotlinx.serialization).
- **Frontend**: Vite + React 19 + TypeScript; Tailwind CSS + shadcn/ui; TanStack Query; TanStack Router (typesafe);
  Zustand; native WebSocket. Built as a static SPA bundled into the Core image — no Node.js in production.
- **Auth**: in-game `/ap register` issues a one-time registration link → user sets password on the web → subsequent
  logins use username + password (Argon2id, Ktor Sessions). The token is for *registration only*, never for ongoing
  login.
- **TLS**: terminated by a reverse proxy in front of Core.

## v1 feature scope

In:

- [ ] Auth: `/ap register` in-game flow → token link → password set → username/password login afterwards. Admin can
  grant access to new MC users from in-game.
- [ ] Server registry — list of connected agents with online/offline + metadata
- [ ] Live per-server console — stream + send commands
- [ ] Player ops — kick, ban, op, gamemode, teleport

Out (later): role hierarchy (everyone with access is admin in v1), plugin management UI, file/config editing, monitoring
charts, world ops, chat moderation, audit log UI, MySQL, password reset flow.

---

## Phase 0 — Project skeleton

### Steps

- [x] Remove `src/Main.java` and `papermc_admin_panel.iml`
- [x] Create `settings.gradle.kts` declaring modules: `shared`, `core`, `agent-paper`, `agent-velocity`
- [x] Create root `build.gradle.kts` with Kotlin plugin, Java 25 toolchain, common repositories
- [x] Create `gradle/libs.versions.toml` (Kotlin, Ktor, Exposed, Flyway, kotlinx.serialization, Paper API, Velocity API,
  etc.)
- [x] Add `.gitignore` covering Gradle build dirs, IDE files, `node_modules/`, `frontend/dist/`,
  `core/src/main/resources/web/`

### Success criteria

- `./gradlew projects` lists all four modules without errors
- `./gradlew :help` exits 0
- No stub files remain at repo root

### Tests

- Smoke: `./gradlew projects` and inspect output for the four expected modules
- Smoke: `./gradlew tasks --all` runs without configuration errors

---

## Phase 1 — Shared protocol module

### Steps

- [x] Scaffold `shared/` with `kotlinx.serialization`
- [x] Define sealed `AgentEnvelope` (Agent → Core): `Hello`, `Heartbeat`, `ConsoleLine`, `PlayerJoin`, `PlayerQuit`,
  `CommandResult`
- [x] Define sealed `CoreEnvelope` (Core → Agent): `RunCommand`, `KickPlayer`, `BanPlayer`, `OpPlayer`, `SetGamemode`,
  `Teleport`
- [x] Define DTOs: `ServerInfo`, `Player`, `AgentMeta`
- [x] Keep dependencies minimal — only `kotlinx.serialization`. Avoid pulling Ktor/Exposed into shared (classloader risk
  in Paper plugin).

### Success criteria

- Module compiles standalone with no transitive Ktor/Exposed dependencies (verified via
  `./gradlew :shared:dependencies`)
- Every envelope subclass serializes to JSON with a stable `type` discriminator
- Round-trip JSON encode → decode produces an equal object for every message type

### Tests

- Unit: round-trip serialization test parameterized over every `AgentEnvelope` and `CoreEnvelope` subclass
- Unit: assert JSON output for one representative of each contains the expected `"type"` discriminator string
- Unit: invalid JSON (missing/unknown discriminator) throws `SerializationException`

---

## Phase 2 — Core: backbone

### Steps

- [x] Scaffold `core/` Ktor application with Logback
- [x] Wire Exposed + HikariCP + SQLite JDBC
- [x] Add Flyway; write `V1__init.sql`:
    - `users` — id, mc_uuid (unique), username, password_hash (nullable until registered), is_admin, created_at
    - `agents` — id, agent_type (paper|velocity), display_name, last_seen_at
    - `registration_tokens` — token, user_id, expires_at, consumed_at
    - `audit_log` — id, user_id, action, target, payload_json, at
- [x] `application.conf` — HTTP port, DB path, agent registration policy
- [x] `GET /api/health` healthcheck
- [x] Static resource serving at `/` from `resources/web/` (empty for now)
- [x] Multi-stage `Dockerfile` (eclipse-temurin:25-jre, copies fat JAR)

### Success criteria

- `./gradlew :core:run` starts Core in under 3 seconds; `curl localhost:8080/api/health` → 200
- Flyway creates all tables on first boot against a fresh SQLite file
- `docker build` succeeds; container runs and serves `/api/health`

### Tests

- Unit: Flyway runs cleanly on an empty in-memory SQLite database (assert all four tables exist with expected columns)
- Integration (Ktor `testApplication`): `GET /api/health` returns 200 with the expected JSON body
- Integration: `GET /` returns 200 with placeholder body when `resources/web/index.html` is present, 404 when absent
- Smoke: `docker run` the built image; healthcheck endpoint replies through the container port

---

## Phase 3 — Core: agent protocol

### Steps

- [ ] Implement `/agent` WebSocket endpoint with `Authorization: Bearer <token>` on upgrade
- [ ] Persist agent rows in `agents` on first `Hello`
- [ ] In-memory `AgentRegistry` mapping `agentId → live connection`
- [ ] Coroutine event bus: agents push events; Core subscribers receive
- [ ] Command dispatch: send `CoreEnvelope` to agent, await matching `CommandResult` by correlation id
- [ ] Heartbeat/timeout — agent marked offline after N missed heartbeats

### Success criteria

- WebSocket client with a valid token connects, sends `Hello`, and a row exists in `agents` with `last_seen_at` updated
- Invalid or missing token rejected at the WS upgrade with 401
- `RunCommand` dispatched from Core completes when the agent replies with the matching correlation id; times out
  otherwise
- Agent disconnect or missed heartbeats causes the registry to mark the agent offline within the configured timeout
  window

### Tests

- Integration: WS test client with valid token connects, sends `Hello`; assert DB row + registry membership
- Integration: WS upgrade with invalid token returns 401; no DB row written
- Integration: dispatch `RunCommand` against a mock agent that echoes a `CommandResult` with the same correlation id;
  assert the suspending dispatch returns the expected result
- Integration: dispatch with no agent reply within timeout; assert the dispatch fails with a timeout exception
- Integration: agent connects, stops sending heartbeats; assert registry marks it offline within the configured timeout

---

## Phase 4 — Core: browser API

### Steps

- [ ] Auth: Ktor Sessions + Argon2id password hashing
- [ ] Registration flow:
    - Admin grants access by creating a user record (`POST /api/users` — MC username + UUID, no password). Bootstrap
      special case: when zero users exist, any op running `/ap register` is auto-granted as the first admin.
    - Player runs `/ap register` in-game → Paper agent calls `POST /api/auth/register/issue` (authenticated by agent
      token, identifies the calling MC UUID) → Core mints a one-time registration token bound to that user → returns a
      URL → agent displays it as a clickable chat message.
    - `GET /api/auth/register/{token}` validates and returns the username for display.
    - `POST /api/auth/register/complete` with `{ token, password }` → Argon2id-hash, store, consume token. User can now
      log in.
- [ ] `POST /api/auth/login` (username + password), `POST /api/auth/logout`, `GET /api/auth/status`
- [ ] `GET /api/servers` — registered agents + live status
- [ ] `GET /api/servers/{id}/players` — proxied to agent
- [ ] `POST /api/servers/{id}/players/{uuid}/{kick|ban|op|gamemode|teleport}`
- [ ] `WS /ws/console/{serverId}` — live console + send command
- [ ] Audit-log write on every mutation

### Success criteria

- Full registration flow (bootstrap and normal grant) yields a user able to log in
- Argon2id hash verifies the original password and rejects others
- Session cookie required for every browser endpoint except `/api/health`, `/api/auth/login`, `/api/auth/status`, and
  the registration endpoints
- Player op endpoints invoke the connected agent and return its `CommandResult`
- Every mutating endpoint writes an `audit_log` row with `user_id`, `action`, and `target`

### Tests

- Unit: Argon2id hash + verify round-trip (positive and negative)
- Unit: registration token validation (expired rejected, consumed rejected, unknown rejected)
- Integration: bootstrap registration end-to-end → first admin can log in
- Integration: granted-user registration end-to-end → can log in
- Integration: protected endpoint without session → 401; with session → 200
- Integration: `GET /api/servers` with one mock agent connected returns its metadata
- Integration: `POST /api/servers/{id}/players/{uuid}/kick` → mock agent receives `KickPlayer` with correct uuid +
  reason; `audit_log` gains a row
- Integration: WS `/ws/console/{id}` subscriber receives `ConsoleLine` events pushed by mock agent; sending a command
  produces a `RunCommand` to the mock agent

---

## Phase 5 — Paper agent

### Steps

- [ ] Scaffold `agent-paper/` targeting Paper 26.1.2+; Kotlin sources, Java 25 toolchain
- [ ] `paper-plugin.yml`, `org.rainbowhunter.adminpanel.agent.paper.Main`
- [ ] `config.yml`: `core.url`, `core.token`, `server.id`, `server.displayName`
- [ ] WebSocket client (Ktor client) with exponential-backoff reconnect
- [ ] Log4j2 appender → stream console lines as `ConsoleLine` events
- [ ] Command bridge: `RunCommand` → run on Bukkit main thread via `Bukkit.getScheduler()`, capture command-sender
  output, return `CommandResult`
- [ ] Listeners for `PlayerJoinEvent` / `PlayerQuitEvent` → push events to Core
- [ ] Player-ops handlers: kick / ban / op / gamemode / teleport — all dispatched on main thread
- [ ] `/ap register` in-game command (bootstrap + normal + already-registered + no-access branches)
- [ ] `/ap grant <player>` admin-only command (resolve UUID, post to Core)

### Success criteria

- Plugin enables on Paper 26.1.2 with no errors in console
- On enable, connects to Core within 5 seconds and registers
- After a Core restart, agent reconnects within configured backoff window
- Console lines reach Core in real time; player join/quit events reach Core
- `RunCommand` from Core runs on the main thread and returns captured output
- Each player op produces the expected in-game effect (kick disconnects with the reason, etc.)
- `/ap register` and `/ap grant` exhibit each branch correctly

### Tests

- Unit: config loader parses representative `config.yml` (happy path + missing required field → error)
- Unit: WebSocket reconnect schedule produces expected exponential-backoff intervals
- Unit: `/ap register` branch logic, table-driven over the user-state matrix
- Integration (MockBukkit): simulate `PlayerJoinEvent` → assert agent pushed `PlayerJoin` with correct UUID + username
- Integration (MockBukkit): receive `KickPlayer` over a fake WS → assert `player.kickPlayer` invoked on the main thread
  with the reason
- Manual: real Paper 26.1.2 server with the plugin; run `/list` from web UI; verify response within ~200ms

---

## Phase 6 — Velocity agent

### Steps

- [ ] Scaffold `agent-velocity/` against Velocity API; Kotlin sources, Java 25 toolchain
- [ ] Same WebSocket client + config pattern as Paper agent (extract shared client into a small `agent-common`
  sub-package if pragmatic)
- [ ] Proxy-level events: player connect/disconnect, server switch
- [ ] Proxy-level ops: kick from proxy, broadcast message

### Success criteria

- Plugin enables on Velocity with no errors
- Connects + reconnects to Core like the Paper agent
- Proxy-level events reach Core
- Proxy kick / broadcast invoked from Core take effect on the proxy

### Tests

- Unit: shared client code reused from `agent-paper` — tests run from `agent-common` if extracted
- Integration: with a stub Core, simulate a player connect event on the Velocity event bus → assert event reaches Core
- Manual: real Velocity proxy with the agent; kick a connected player from the web UI; verify they disconnect

---

## Phase 7 — Frontend

### Steps

- [ ] Scaffold `frontend/` via `npm create vite@latest -- --template react-ts`
- [ ] Install Tailwind CSS, configure `tailwind.config.ts`
- [ ] Install shadcn/ui CLI; add components: button, input, table, dialog, dropdown-menu, toast, tabs
- [ ] Install TanStack Query, TanStack Router, Zustand
- [ ] App shell: sidebar with server list, main content area, dark mode toggle
- [ ] Auth pages:
    - `/register/:token` — validates token via API, displays username, password form, posts to register/complete
    - `/login` — username + password
    - First-run hint: if `GET /api/auth/status` reports zero users, login screen shows instructions to have an op run
      `/ap register` in-game
    - Admin user-list page (read-only view of who has access; granting happens via `/ap grant` in-game)
- [ ] Server registry dashboard — TanStack Query for list, WS subscription for status changes
- [ ] Live console view — WS subscription, virtualized scrollback, command input box
- [ ] Player ops table — players across servers, row actions for kick/ban/op/gamemode/teleport, confirm dialogs

### Success criteria

- `npm run build` succeeds and produces a `dist/` directory
- `tsc --noEmit` passes with zero errors
- Every page renders against a running Core dev instance without browser console errors
- Auth flow works end-to-end against the real Core
- Dark mode toggle persists across reloads

### Tests

- Unit (Vitest + React Testing Library): register form validation (password length, confirmation match)
- Unit: server-card component renders online/offline states correctly from props
- Unit: console line list correctly virtualizes a 10k-line buffer (only the visible window mounts)
- E2E (Playwright): full bootstrap → register → login flow against a Core test instance
- E2E: console view receives a streamed line; command sent reaches the server
- E2E: kick action confirms, then the player row disappears

---

## Phase 8 — Build glue + deploy

### Steps

- [ ] Add Gradle Node plugin (`com.github.node-gradle.node`) to `frontend/build.gradle.kts`
- [ ] Gradle task `:frontend:syncDist` runs `npm ci && npm run build`, copies `dist/` → `core/src/main/resources/web/`
- [ ] Wire `:core:processResources` to depend on `:frontend:syncDist`
- [ ] `docker/docker-compose.yml`: `core` service with mounted volume for SQLite + config; expose port 8080

### Success criteria

- `./gradlew assemble` from a clean checkout produces the Core fat JAR with the SPA embedded under `web/`
- `docker compose up core` exposes the SPA at `http://localhost:8080/`
- Final end-to-end verification (below) passes against a real Paper 26.1.2 + Velocity setup

### Tests

- CI: GitHub Actions workflow runs `./gradlew check assemble` on every PR
- CI: integration tests for `core` and unit tests for `shared` and agents run on every PR
- Manual end-to-end: matches the Verification section below

---

## Open questions (to resolve at or before implementation)

- [ ] v1 feature scope: accept the four above, trim, or extend?

## Verification (post Phase 8)

- [ ] `./gradlew assemble` succeeds and produces: `core/build/libs/admin-panel-core-*.jar`,
  `agent-paper/build/libs/admin-panel-agent-paper-*.jar`, `agent-velocity/build/libs/admin-panel-agent-velocity-*.jar`
- [ ] `docker compose up core` starts Core and serves the SPA at `http://localhost:8080`
- [ ] Drop `agent-paper-*.jar` into a Paper 26.1.2 server's `plugins/`, fill in Core URL + token, start Paper — server
  appears in Core's registry within 5 seconds
- [ ] As an in-game op, run `/ap register` on the Paper server → click the chat link → set a password. Log in via the
  web with that username + password. Open the Paper server's console; run `/list`; response appears within ~200ms.
- [ ] Kick a test player via player-ops; player leaves with the kick reason
- [ ] Stop Paper; server shows offline in registry within 10 seconds
- [ ] Same flow works for Velocity via `agent-velocity`
