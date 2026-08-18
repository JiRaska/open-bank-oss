# Data

## Schema

Dedicated PostgreSQL schema `openbank_security` in the `openbank` database (shared cluster, schema-per-service isolation).

**The service persists nothing operational.** After `V4` the schema holds `flyway_schema_history`
and no other table. There is no entity, no repository and no JPA mapping anywhere in the service —
the database exists solely so Flyway has somewhere to record its own migration history and so the
readiness probe has a datasource to check.

Consequently:

- **Scan results** (`ServiceScanResult`, `PlatformSecurityReport`) live in `SecurityScannerService`
  in `ConcurrentHashMap` fields (`lastResults`, `lastReport`). They are lost on pod restart and
  rebuilt by the next scheduled scan (up to 30 minutes later, 2 minutes after startup on a cold pod).
- **ICT incidents** live in `IctIncidentService` in a `ConcurrentHashMap` (`store`). They are lost on
  pod restart and are **not** recoverable — nothing else holds a copy except the Kafka event that was
  emitted at the time.
- There is no scan history. `GET /api/v1/security/report` returns the last in-memory report only.

There is no ER diagram to draw: the service owns no tables.

> Until #4709 this page described a `security_outbox` table and an `ict_incidents` table. The outbox
> existed but was never written to (0 rows ever, and 0 records ever produced to its topic) and has
> been dropped by `V4`; the `ict_incidents` table never existed at all.

## Migrations

| Script | What it does | Status |
|---|---|---|
| `V2__create_security_outbox.sql` | Created `security_outbox` with indexes on `(status, created_at)` and `aggregate_id` | Applied on the live database; superseded by V4 |
| `V3__hibernate_sequences.sql` | Created the Hibernate/Panache sequence used for the outbox surrogate key | Applied; the sequence is dropped by V4 |
| `V6__drop_security_outbox.sql` | `DROP TABLE security_outbox` + `DROP SEQUENCE security_outbox_seq` — the outbox had no producer (#4709) | The current head |

> V1 is absent — the scanner was stateless in its first iteration and V2 is the first migration that
> landed. V2 and V3 are deliberately kept as files rather than deleted: both are recorded as applied
> in the live `flyway_schema_history`, and removing an applied migration's file fails Flyway
> validation exactly as editing one fails the checksum.

## Where each piece of state lives

| Data | Storage | Lifetime |
|---|---|---|
| `ServiceScanResult` (per service) | In-memory `ConcurrentHashMap` | Until pod restart; rebuilt by next scan |
| `PlatformSecurityReport` | In-memory (last result only) | Until pod restart; rebuilt by next scan |
| `SecurityFinding` | In-memory (part of results) | Until pod restart |
| `IctIncident` | In-memory `ConcurrentHashMap` | Until pod restart — **not recoverable** |

## Indexes

None — the service has no tables of its own.

## Retention

There is nothing to retain in this service's database. The only durable trace of its activity is the
`openbank.security.ict.incident` Kafka topic and whatever `audit-service` stores from it; that
retention is owned by audit-service, not here.

Anyone needing a durable ICT incident register — which DORA Art. 17 evidence realistically requires —
should treat the current in-memory store as a gap, not as a control.

## PII considerations

An `IctIncident` may carry:

- `assignedTo` — email/name of the assigned engineer (internal employee data)
- `description` — free text that could reference customer-facing systems

These fields are internal operator data, not customer PII, and are never written to a database here.
They do travel on the Kafka event.

## Size estimates

- Database: `flyway_schema_history` only — 3 rows.
- In-memory scan results: 27 services × ~5 KB each = ~135 KB (trivial).
- In-memory ICT incidents: bounded only by pod lifetime; expected tens per month.
