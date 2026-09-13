# Data

## Schema

Dedicated PostgreSQL schema `openbank_security` in the `openbank` database (shared cluster, schema-per-service isolation).

**The service persists one table: `ict_incidents`.** After `V5` the schema holds
`flyway_schema_history` and `ict_incidents`. There is no entity, repository or JPA mapping for
anything else in the service — scan results are still in-memory only.

Consequently:

- **Scan results** (`ServiceScanResult`, `PlatformSecurityReport`) live in `SecurityScannerService`
  in `ConcurrentHashMap` fields (`lastResults`, `lastReport`). They are lost on pod restart and
  rebuilt by the next scheduled scan (up to 30 minutes later, 2 minutes after startup on a cold pod).
- **ICT incidents** are durable in the `ict_incidents` table (issue #4728). They used to live in
  `IctIncidentService`'s `ConcurrentHashMap` (`store`) and were lost on every pod restart; that is
  fixed — an incident, its containment/resolution timestamps and its
  `reported_to_regulator`/`regulatory_report_id` state now survive a restart.
- There is no scan history. `GET /api/v1/security/report` returns the last in-memory report only.

`ict_incidents` is the one table this service owns:

```
ict_incidents
├── id                      UUID PRIMARY KEY  (app-assigned, UUID.randomUUID())
├── title                   TEXT NOT NULL
├── description             TEXT NOT NULL
├── category                VARCHAR(64) NOT NULL
├── severity                VARCHAR(32) NOT NULL
├── status                  VARCHAR(32) NOT NULL
├── affected_services       TEXT NOT NULL   (comma-joined service names)
├── detected_at             TIMESTAMPTZ NOT NULL
├── reported_at             TIMESTAMPTZ NOT NULL
├── contained_at            TIMESTAMPTZ
├── resolved_at             TIMESTAMPTZ
├── rto_minutes             INTEGER
├── rpo_minutes             INTEGER
├── reported_to_regulator   BOOLEAN NOT NULL DEFAULT FALSE
├── regulatory_report_id    TEXT
├── assigned_to             TEXT
├── created_at              TIMESTAMPTZ NOT NULL
└── updated_at              TIMESTAMPTZ NOT NULL
```

No other table references it and it references nothing else — a single-node diagram, not worth
drawing as a graph.

> Until #4709 this page described a `security_outbox` table and an `ict_incidents` table, and
> claimed both were fictional. The outbox existed but was never written to (0 rows ever, and 0
> records ever produced to its topic) and was dropped by `V4`; `ict_incidents` did not exist at that
> point either. `V5` (issue #4728) then created the real `ict_incidents` table described above —
> this page is updated to match.

## Migrations

| Script | What it does | Status |
|---|---|---|
| `V2__create_security_outbox.sql` | Created `security_outbox` with indexes on `(status, created_at)` and `aggregate_id` | Applied on the live database; superseded by V4 |
| `V3__hibernate_sequences.sql` | Created the Hibernate/Panache sequence used for the outbox surrogate key | Applied; the sequence is dropped by V4 |
| `V4__drop_security_outbox.sql` | `DROP TABLE security_outbox` + `DROP SEQUENCE security_outbox_seq` — the outbox had no producer (#4709) | Applied out of order (#5628) |
| `V5__create_ict_incidents.sql` | Created `ict_incidents` (columns above) + indexes on `created_at`, `status`, `severity` — moves the DORA ICT incident register out of the in-memory map (#4728) | The current head |

> V1 is absent — the scanner was stateless in its first iteration and V2 is the first migration that
> landed. V2 and V3 are deliberately kept as files rather than deleted: both are recorded as applied
> in the live `flyway_schema_history`, and removing an applied migration's file fails Flyway
> validation exactly as editing one fails the checksum.

> `ict_incidents` has no Hibernate sequence: its id is application-assigned (`UUID.randomUUID()` in
> `IctIncidentService.reportIncident`), not `@GeneratedValue`, so the entity is
> `PanacheEntityBase` with an explicit `@Id` rather than `PanacheEntity`. Updates go through
> `Panache.getSession().flatMap { it.merge(entity) }` — `persist()` on an assigned id would schedule
> an INSERT for every save and fail every status transition after the first with a duplicate-key
> error (see `IctIncidentEntity`, `IctIncidentRepositoryImpl.save`).

## Where each piece of state lives

| Data | Storage | Lifetime |
|---|---|---|
| `ServiceScanResult` (per service) | In-memory `ConcurrentHashMap` | Until pod restart; rebuilt by next scan |
| `PlatformSecurityReport` | In-memory (last result only) | Until pod restart; rebuilt by next scan |
| `SecurityFinding` | In-memory (part of results) | Until pod restart |
| `IctIncident` | `ict_incidents` table (PostgreSQL) | Durable — survives pod restart |

## Indexes

`ict_incidents` carries three, matching how `GET /api/v1/ict-incidents` filters and sorts:

| Index | Column(s) | Why |
|---|---|---|
| `idx_ict_incidents_created_at` | `created_at DESC` | The list endpoint orders by `created_at DESC` |
| `idx_ict_incidents_status` | `status` | The list endpoint filters by status |
| `idx_ict_incidents_severity` | `severity` | The list endpoint filters by severity |

No other table exists, so these are the service's only indexes.

## Retention

`ict_incidents` rows are retained indefinitely — there is no TTL, archival job or delete path in the
service. The only other durable trace of ICT-incident activity is the
`openbank.security.ict.incident` Kafka topic and whatever `audit-service` stores from it; that
retention is owned by audit-service, not here.

DORA Art. 17 evidence needs a durable ICT incident register; `ict_incidents` (since #4728, `V5`) is
that register. Before `V5`, the in-memory store was correctly flagged here as a gap, not a control —
that gap is closed.

## PII considerations

An `IctIncident` may carry:

- `assignedTo` — email/name of the assigned engineer (internal employee data)
- `description` — free text that could reference customer-facing systems

These fields are internal operator data, not customer PII. They are written to `ict_incidents`
(`assigned_to`, `description` columns) and also travel on the Kafka event.

## Size estimates

- Database: `flyway_schema_history` (4 rows) + `ict_incidents`, expected tens of rows per month,
  each row well under 1 KB outside of `description` — low hundreds of KB per year, trivial for a
  dedicated schema.
- In-memory scan results: 27 services × ~5 KB each = ~135 KB (trivial).
