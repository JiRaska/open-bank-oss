# Data

## Datastore

- **Engine:** PostgreSQL (reactive `pg-client` for runtime, JDBC for Flyway).
- **Database:** `openbank_mcp`.
- **Generation:** `hibernate-orm.database.generation: none` — the schema is owned by Flyway, never by Hibernate DDL. `flyway.migrate-at-start: true`, so a deployed pod applies pending migrations on boot.
- **Classification:** `confidential` (`governance.yaml`). **Retention:** 13 months. `evidenceExported: false` — nothing here is part of a regulatory evidence set.

## Tables

### `agent_session`

ADR-0224 D2: a staff on-behalf-of session issued after step-up, bounding the operator's roles to a ceiling with a purpose and an expiry. Revocation must be instant, so the resolver validates the session **live on every OBO call** rather than trusting a token's lifetime.

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` PK | **application-assigned**, not generated — see the write-path note below |
| `subject` | `VARCHAR(255)` NOT NULL | the staff principal the session acts for |
| `role_ceiling` | `TEXT` NOT NULL | upper bound on the roles the session may exercise |
| `client_id` | `VARCHAR(255)` NOT NULL | the OAuth client the session was issued to |
| `jti` | `VARCHAR(255)` | JWT ID of the exchanged token (V2) |
| `purpose` | `VARCHAR(500)` | free text, operator-supplied |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |
| `expires_at` | `TIMESTAMPTZ` NOT NULL | |
| `revoked_at` | `TIMESTAMPTZ` | NULL means active |

There is no outbox table: this service publishes no domain events.

## Indexes

| Index | Shape |
|---|---|
| `idx_agent_session_subject` | `(subject) WHERE revoked_at IS NULL` — partial, matching the "active sessions for this operator" read |
| `idx_agent_session_jti` | `UNIQUE (jti) WHERE jti IS NOT NULL` — one session per exchanged token, and rows predating the binding are not forced to collide on NULL |

## Flyway migrations

| Version | File | Purpose | Rollback |
|---|---|---|---|
| V1 | `V1__agent_sessions.sql` | `agent_session` + `idx_agent_session_subject` | `DROP TABLE agent_session;` (drops the index with it) |
| V2 | `V2__agent_session_jti.sql` | adds `jti` + `idx_agent_session_jti` | `ALTER TABLE agent_session DROP COLUMN jti;` — PostgreSQL drops the dependent index with the column, so an explicit `DROP INDEX` first is redundant |

The two rollbacks differ in blast radius, and it is worth being precise about which is which. **V1 discards every session** — acceptable rather than alarming: a session is a short-lived authorization grant, so losing one costs an operator a re-authentication, not data. **V2 discards only the token binding**; the sessions survive, but every row loses its `jti`, so the resolver can no longer tie an exchanged token to its session and each affected session must be re-established. **Never rewrite an applied migration** — Flyway checksums the whole file, comments included, so an edit turns into a `checksum mismatch` at the next boot (repository-wide rule).

## Write path

`id` is assigned by the application, not by `@GeneratedValue`. Hibernate cannot tell a transient entity from a detached one when the id is already set, so `persist()` schedules an INSERT for **every** save and any lifecycle transition (bind, revoke) fails at flush with `duplicate key value violates ... _pkey`. `AgentSessionRepository` therefore uses `merge` on the update path, and says so at the call site.

This is invisible to a test that mocks the repository — the fleet has shipped exactly this defect more than once and only a real-database integration test caught it.

## PII inventory

| Field | Classification | Handling |
|---|---|---|
| `subject` | staff identifier | operational; identifies a bank employee, not a customer |
| `client_id` | OAuth client id | not personal data |
| `jti` | token identifier | short-lived credential material — never log it |
| `purpose` | potentially sensitive free text | operator-entered; may name a customer or a case, so treat as confidential |

No customer identifiers, balances or payment data are stored here: this table describes **who may act**, never what they read. The data the session grants access to lives in the services the MCP tools call.
