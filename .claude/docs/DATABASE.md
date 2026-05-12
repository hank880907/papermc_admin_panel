# Database

## Approach

- **Flyway** manages schema from day 1. Migrations are plain SQL files in
  `core/src/main/resources/db/migration/`, named `V<n>__<short_description>.sql`. Flyway tracks applied versions in the
  auto-created `flyway_schema_history` table and runs pending migrations at Core startup.
- **Exposed** (Kotlin typed JDBC DSL) for queries at the application layer. Lightweight, type-safe, no annotation magic;
  coexists with a Flyway-managed schema (Exposed does not create or alter tables in production).
- **HikariCP** for connection pooling.
- **SQLite v1**, MySQL later as a driver swap. All committed SQL is DB-agnostic.

## Why these choices?

- **Flyway over a code-defined schema**: plain SQL migrations are reviewable, diffable, and database-agnostic in a way
  framework-generated DDL is not. Versioned migrations also give a stable upgrade path for existing deployments.
- **Exposed over raw JDBC**: type-safe column references and result extraction without the weight (or magic) of an ORM.
- **Exposed over Hibernate/JPA**: no annotation processing, no `EntityManager`, no L1/L2 cache surprises. The schema
  lives in Flyway files; Exposed just maps queries onto it.
- **SQLite first**: zero-ops for local dev and small deployments. MySQL is a swap when scale requires it.

## Conventions

- **Tables**: `snake_case`, plural (`users`, `agents`, `registration_tokens`, `audit_log`).
- **Columns**: `snake_case`, singular (`mc_uuid`, `created_at`).
- **Primary keys**: `id INTEGER NOT NULL PRIMARY KEY`. No `AUTOINCREMENT` keyword — SQLite still auto-assigns rowids,
  MySQL will need a vendor-specific migration to add `AUTO_INCREMENT` when we cut over. Stays portable in committed SQL.
- **Timestamps**: `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`, treated as UTC. Read/write through Exposed's
  `exposed-java-time`.
- **Booleans**: `BOOLEAN NOT NULL DEFAULT 0`. SQLite stores as INTEGER, MySQL as TINYINT — both accept the keyword and
  the literal `0`/`1`.
- **Foreign keys**: declared explicitly with `FOREIGN KEY (col) REFERENCES other_table(col)`. No `ON DELETE` clauses in
  v1 — we don't expect deletes of users or agents in v1, and lifecycle rules can be added later.
- **No vendor-specific syntax** in committed migrations: no `AUTOINCREMENT`, no `AUTO_INCREMENT`, no SQLite pragmas, no
  MySQL `ENGINE=`, no PostgreSQL `SERIAL`. If a future change genuinely requires it, ship it as a per-vendor migration
  (see [SQLite → MySQL](#sqlite--mysql) below).

## v1 schema

Four tables. The structured definition is in [`db-schema.json`](db-schema.json); the SQL that is actually applied lives
in `core/src/main/resources/db/migration/V1__init.sql`. Summary:

- **`users`** — Application users with web-panel access. `mc_uuid` is the canonical identifier; `username` is the MC
  name captured at registration time and is **not** unique (MC name swaps between accounts are possible).
  `password_hash`
  is null until the registration flow completes. `is_admin` is reserved for the post-v1 role hierarchy; in v1 every user
  with a row is effectively an admin.
- **`agents`** — Registered Paper/Velocity instances. Written on first `Hello` and updated on each heartbeat.
  `agent_type`
  is `'paper' | 'velocity'` by convention (not enforced by `CHECK`, to keep the migration portable).
- **`registration_tokens`** — One-time URLs minted when an in-game player runs `/ap register`. Consumed when the user
  sets a password; reuse is rejected via `consumed_at`.
- **`audit_log`** — Append-only record of mutations. `action` is a stable dotted code (`player.kick`, `user.grant`),
  `target` is a free-form identifier, `payload_json` carries action-specific context.

## Migration policy

- One migration per change: `V<n>__<short_description>.sql`.
- **Committed migrations are immutable.** Never edit a `V<n>__` file that has shipped — write `V<n+1>__` instead.
- Prefer additive changes: new tables, nullable columns, new indices.
- Breaking changes are split across releases: add new shape → backfill → remove old shape in a later migration.
- No `repeatable` (`R__`) migrations in v1; revisit if we add views or seed data.

## SQLite → MySQL

- All v1 SQL works on both engines unchanged.
- The one known divergence is auto-increment semantics: SQLite hands out rowids for `INTEGER PRIMARY KEY`; MySQL does
  not. When we move to MySQL, a vendor-specific Flyway script (`V<n>__add_autoincrement.mysql.sql`, using the
  `vendor`-suffix naming) will add `AUTO_INCREMENT` to existing PK columns.
- The HikariCP/JDBC URL changes; everything else (Exposed queries, repository code) should be untouched.

## Operational notes

- DB file path is configured via `adminpanel.db.path` in `application.conf` (default `data/admin-panel.db`, override
  with `ADMINPANEL_DB_PATH`).
- Core creates the parent directory at startup if missing.
- SQLite holds a single-writer lock; the HikariCP pool size is small (5) and write transactions should be brief. If
  contention shows up under load, that's the signal to plan the MySQL cutover.
- The `flyway_schema_history` table is created automatically on first run; do not edit it by hand.

## Adding a new migration

1. Create `core/src/main/resources/db/migration/V<n>__<short_description>.sql` (next integer after the highest existing
   `V`).
2. Write portable SQL — see [Conventions](#conventions). No vendor-specific syntax.
3. If the change adds or changes a table, update [`db-schema.json`](db-schema.json) to match. The JSON is the structured
   reference for the v1-style summary in this doc.
4. Add or update a `MigrationsTest` assertion if a new table/column needs structural coverage.
5. Restart Core; Flyway applies the new migration on startup.