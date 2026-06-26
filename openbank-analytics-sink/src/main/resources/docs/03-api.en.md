# API

> **Contract status:** there is **no `openapi.yaml`** in this service yet — the REST contract is not formally pinned (the service builds `quarkus-smallrye-openapi`, so a generated spec is served at runtime via Swagger UI at `/api/docs`, but no checked-in `openapi.yaml` exists). The endpoints below are documented **directly from the JAX-RS resource classes** under `infrastructure/rest`. Formalising the contract into a checked-in `openapi.yaml` + contract test is a follow-up (see [05 — Operations](./05-operations.md)).

This service exposes **no public/customer API**. The REST surface is an **operator/audit/compliance** surface only, and is **not** in any payment path.

- **Base path / versioning:** `/api/v1/...` — API major version `1` (`openbank.api.version=1`). `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.
- **App port:** 8134. **Management port:** 8086 (`/q`).
- **Content type:** `application/json`.
- **Auth:** Keycloak OIDC (RS256 bearer). Every verb is role-gated; **no `@PermitAll` mutations** (K7 audit-trail rule).

## Reconciliation — `/api/v1/analytics/reconciliation`

Roles: `ROLE_AUDITOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/run` | Trigger a reconciliation run (`source="manual"`). Read-only comparison of warehouse vs source-of-record per-aggregate `max(version)`; records a `ReconciliationResult` (also runs on cron, default `0 30 2 * * ?`). |
| `GET` | `/last` | Last reconciliation evidence. `204 No Content` if none yet. |

## Backfill / recovery loads — `/api/v1/analytics/backfill`

Roles: `ROLE_ADMIN`, `ROLE_AUDITOR`. **Four-eyes (maker-checker)** — proposer and approver must differ.

| Method | Path | Description |
|---|---|---|
| `POST` | `/proposals` | Step 1 — propose a reload. Body `ReloadProposalDto { kind, from?, to?, aggregateType?, aggregateId?, reason }`. `kind` ∈ `BACKFILL` / `CORRECTION` / `INITIAL_LOAD` (`STREAM` rejected as the live path). Returns the `PROPOSED` proposal id. |
| `POST` | `/proposals/{id}/approve` | Step 2 — a **different** operator approves. Self-approval ⇒ `409 Conflict` (`MakerCheckerViolation`). |
| `POST` | `/proposals/{id}/reject` | Reject a pending proposal. |
| `POST` | `/proposals/{id}/execute` | Step 3 — execute an `APPROVED` proposal; runs the reload and writes a `backfill_audit` row, then marks `EXECUTED`. |
| `GET` | `/proposals` | List all proposals. |
| `GET` | `/proposals/{id}` | Get one proposal (`404` if absent). |
| `GET` | `/last` | Last actually-executed backfill report (`204` if none). |

## GDPR erasure — `/api/v1/analytics/erasure`

Roles: `ROLE_COMPLIANCE`, `ROLE_ADMIN`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/` | GDPR Art. 17 erasure against the analytics layer. Body `ErasureRequestDto { aggregateType, aggregateId }`. Returns an `ErasureDecision`: either crypto-shredded (`erased=true`) or **refused** under a statutory hold (Art. 17(3)(b)) with an auditable `legalBasis`/`explanation`. |

## Error model

| Status | Meaning |
|---|---|
| `400 Bad Request` | Unknown reload `kind`, missing required `from`, or `STREAM` used as a reload kind. |
| `401 / 403` | Missing/invalid bearer token, or role not permitted for the verb. |
| `404 Not Found` | Proposal id not found. |
| `409 Conflict` | Maker-checker violation (self-approval / illegal proposal transition), via `MakerCheckerExceptionMapper`. |
| `204 No Content` | `/last` reads when no result exists yet. |

## Idempotency / delivery

- **Ingestion** (Kafka, not REST) is at-least-once; `eventId` is the dedupe key and ClickHouse `ReplacingMergeTree` collapses duplicates. There is no `Idempotency-Key` REST header here (unlike money-path services) — the operator verbs are either naturally idempotent reads or guarded by the maker-checker state machine.
