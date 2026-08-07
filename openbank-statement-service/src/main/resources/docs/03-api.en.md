# API

The REST contract is formalised in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version` **0.3.0**). All endpoints are under `/api/v1` (the URL major matches `openbank.api.version`, ADR-0048). Swagger UI is served at `/api/docs`.

## Authentication & roles

Keycloak OIDC (RS256 bearer). The resources are role-gated with `@RolesAllowed`:

- **Reads** (list, render, export, close-run queries): `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_API`.
- **Mutations** (period-close, manual close-run trigger): `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API`.

Outbound calls to transaction / balance / account / party services carry a separate **client-credentials** M2M token (`openbank-services`, `ROLE_OPERATOR`), attached by `OidcClientRequestReactiveFilter`.

## Endpoints — Statements

### `POST /api/v1/statements/{accountId}/close`

Close a month for **every pocket** of an account. Assigns the next legal/electronic sequence per pocket, snapshots opening/closing balances, and runs the fail-closed reconciliation.

- Query params: `from` (date, required), `to` (date, required).
- **Idempotent** on `(accountId, pocketCurrency, periodFrom, periodTo)` — a re-run returns the existing close, never a new sequence.
- `200` → array of `StatementPeriod` (one per pocket).
- `409` → fail-closed reconciliation mismatch; **no statement is issued** (body `{ "error": "..." }`).

### `POST /api/v1/statements/{accountId}/{currency}/restate`

Restate a closed period after a correction to the underlying booked data (ADR-0035 §D). The existing record is **never edited**: the service re-reads the booked entries and balance-service's closing balance, reconciles fail-closed exactly as a first close does, and issues a **new** close carrying the next legal sequence with `supersedesSequence` pointing at the record it replaces; that record flips to `SUPERSEDED` in the same transaction.

- Roles: `ROLE_OPERATOR`, `ROLE_ADMIN` (an operator action — deliberately **not** `ROLE_API`).
- Query params: `from` (date, required), `to` (date, required).
- If the recomputed figures are unchanged, **no sequence is burnt** and the standing record is returned as-is.
- The superseded record is retained and stays renderable by its own legal sequence for its full retention period.
- `200` → the standing `StatementPeriod` after the restatement.
- `404` → no closed period for that window; a restatement never mints a first close.
- `409` → fail-closed reconciliation mismatch; the standing close is left **untouched**.

### `GET /api/v1/statements/{accountId}`

List the retained period-close records for an account. `200` → array of `StatementPeriod`.

### `GET /api/v1/statements/{accountId}/{currency}/{legalSequence}`

Render a closed statement **on demand**. Nothing is stored; the response is rendered deterministically from the closed period plus replayed booked entries.

- Query param: `format` ∈ `CAMT_053` | `MT940` | `PDF` (default `PDF`).
- `200` → rendered statement (`application/xml` for camt.053, `text/plain` for MT940/PDF — the content type is set from the renderer output).
- `404` → no closed statement with that sequence (body `{ "error": "..." }`).

### `GET /api/v1/statements/{accountId}/{currency}/export`

Ad-hoc, **non-sequenced informational** export for an arbitrary date range (legal/electronic sequence = 0). Same `format` options.

- Query params: `from` (date), `to` (date), `format`.
- `200` → rendered informational export (carries no legal sequence).

## Endpoints — Statement close runs (operational telemetry, ADR-0069 D3)

### `GET /api/v1/statements/close-runs`
Recent scheduled/manual close runs, newest first. Query `limit` (default 20). `200` → array of `CloseRun`.

### `POST /api/v1/statements/close-runs`
Trigger a manual catch-up close pass now (operator retry). Runs a full self-healing pass; per-pocket failures are isolated, recorded, and emitted as `period.close_failed`. `202` → the `CloseRun` that was executed.

### `GET /api/v1/statements/close-runs/latest`
The most recent close run. `200` → `CloseRun`; `204` → the cadence has never run.

### `GET /api/v1/statements/close-runs/{runId}/failures`
Per-pocket failures recorded within a close run. `200` → array of `CloseFailure`.

## Schemas

### `StatementPeriod` (the only persisted statement artefact)
`id`, `accountId`, `pocketCurrency` (ISO-4217, 3), `periodFrom`, `periodTo`, `legalSequenceNumber`, `electronicSequenceNumber`, `openingBalance`, `closingBalance`, `entryCount`, `status` ∈ `CLOSED` | `SUPERSEDED`, `supersedesSequence` (nullable), `closedAt`.

### `CloseRun`
`id`, `trigger` ∈ `SCHEDULED` | `MANUAL`, `status` ∈ `RUNNING` | `COMPLETED` | `COMPLETED_WITH_FAILURES`, `periodFrom`/`periodTo` (nullable), `accountsEnumerated`, `pocketsClosed`, `pocketsFailed`, `pocketsSkipped`, `startedAt`, `finishedAt` (nullable).

### `CloseFailure`
`id`, `runId`, `accountId`, `pocketCurrency`, `periodFrom`, `periodTo`, `reason` ∈ `RECONCILIATION` | `UPSTREAM` | `UNKNOWN`, `detail` (nullable), `failedAt`.

## Error model

| Status | When | Body |
|---|---|---|
| `200` | success | resource / array |
| `202` | manual close run accepted | `CloseRun` |
| `204` | latest close run requested, cadence never ran | (empty) |
| `404` | render: no closed statement with that sequence | `{ "error": "..." }` |
| `409` | period-close: fail-closed reconciliation mismatch | `{ "error": "..." }` |

`409` is the load-bearing failure mode: the computed closing (`opening ± booked net movement`) disagreed with balance-service's reported closing, so **no period record and no event are produced** — a self-inconsistent legal statement is never emitted.

## Versioning

Two independent axes (ADR-0048): the **release** version (`version.txt` = 0.3.0, owned by release-please) and the **API contract** version (`openapi.yaml: info.version` = 0.3.0). The URL major `/api/v1` equals `openbank.api.version`. `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.

## Events

- **Out** — `account.statement.period.closed.v1` on Kafka topic `openbank.statement.event` (emitted transactionally via the outbox on every clean close). The operational cadence may additionally emit `period.close_failed` per failed pocket.
- **In** — `openbank.accounts.account.created` (`AccountCreated`), consumed into the local `account_registry` projection.
