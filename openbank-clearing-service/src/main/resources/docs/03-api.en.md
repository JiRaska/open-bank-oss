# API

The REST contract is defined in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version 1.0.0`). All paths are under `/api/v1/clearing` — the URL major version (`v1`) tracks the OpenAPI contract major (ADR-0048). Media type is `application/json`. Authentication is a Keycloak bearer JWT (`bearerAuth`).

> **Note:** the `openapi.yaml` `servers` block lists `http://localhost:8114`, but the running app HTTP port is **8124** (`application.yaml: quarkus.http.port`). Treat 8124 as authoritative for local runs; the server URL in the contract is a known discrepancy.

## Endpoints

| Method | Path | Roles (`@RolesAllowed`) | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/clearing/submit` | `SERVICE`, `PAYMENTS`, `ADMIN` | Submit a payment for clearing → `201 Created` with the new `ClearingItem` |
| `GET` | `/api/v1/clearing/batches?status=&page=&size=` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | List clearing batches (optional `status`, paged) |
| `GET` | `/api/v1/clearing/batches/{id}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Get a batch by id (`404` if absent) |
| `GET` | `/api/v1/clearing/batches/{id}/items` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | List items in a batch |
| `POST` | `/api/v1/clearing/batches/{id}/settle` | `PAYMENTS`, `ADMIN` + `@Authorize(clearingBatch.settle)` | Settle a batch → status SETTLED, emits batch-settled |
| `POST` | `/api/v1/clearing/cycle/trigger?rail=SEPA_SCT` | `PAYMENTS`, `ADMIN` | Trigger a clearing cycle for a rail |
| `GET` | `/api/v1/clearing/positions/{cycleId}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Settlement positions for a cycle |
| `GET` | `/api/v1/clearing/items/{id}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | Get a clearing item by id (`404` if absent) |
| `GET` | `/api/v1/clearing/items/by-payment/{paymentId}` | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | List clearing items for a payment |

`rail` query / enum values: `SEPA_SCT`, `SEPA_SCT_INST`, `SWIFT`, `DOMESTIC`, `INTERNAL` (the OpenAPI `SubmitPaymentRequest.rail` enum lists `SEPA_SCT`, `SEPA_SCT_INST`, `CZ_DOMESTIC`, `SWIFT`; the domain enum uses `DOMESTIC`/`INTERNAL` — note the `CZ_DOMESTIC` vs `DOMESTIC` naming discrepancy between contract and code).

## Submit request body

`SubmitPaymentRequest` (`POST /clearing/submit`):

| Field | Type | Required | Notes |
|---|---|---|---|
| `paymentId` | UUID | yes | upstream payment id |
| `paymentReference` | string | yes (code) | reference (`VARCHAR(64)`) |
| `debtorIban` | string | yes (code) | up to 34 chars |
| `creditorIban` | string | yes (code) | up to 34 chars |
| `debtorBic` / `creditorBic` | string | no | up to 11 chars |
| `amount` | number (BigDecimal) | yes | must be `> 0` (DB CHECK) |
| `currency` | string (CHAR(3)) | no | default `EUR` |
| `rail` | enum | no | default `SEPA_SCT` |
| `valueDate` | date | no | defaults to today if omitted |
| `endToEndId` | string | no | up to 35 chars |
| `remittanceInfo` | string | no | up to 140 chars |

(The OpenAPI schema marks `paymentId, rail, amount, currency` as required; the Kotlin `SubmitPaymentRequest` additionally requires the references and IBANs as non-null. The contract is **not yet fully formalized** — request/response schemas in `openapi.yaml` are minimal and several responses only document `200`.)

## Idempotency

`Idempotency-Key` is configured as an allowed request header (CORS + `quarkus.http.cors.headers`) and Redis (Valkey) is wired as a dependency, mirroring the platform idempotency pattern. The submit/settle handlers in the current code do not show an explicit idempotency-store guard — treat end-to-end idempotency enforcement as **partial / TBD** and rely on the upstream payment service's idempotency for now.

## Error model

The resource returns reactive `Uni<Response>`:

- `201 Created` — successful `submit`.
- `200 OK` — successful reads, `settle`, `cycle/trigger`.
- `404 Not Found` — `getBatch` / `getItem` when the id does not exist.
- `500` with body `{ "error": "<message>" }` — failures on `submit`, `settle`, `triggerCycle` are recovered into a server-error response carrying the exception message (`onFailure().recoverWithItem`). A typed RFC-7807 problem+json model is **not yet** in place here.

## Versioning

- **API contract version:** `openapi.yaml: info.version = 1.0.0`; URL major `/api/v1` == contract major (ADR-0048).
- **Release version:** `version.txt = 0.2.0` (independent axis, owned by release-please).
- `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.
