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
| `POST` | `/api/v1/clearing/cycle/trigger?rail=SEPA_SCT` | `PAYMENTS`, `ADMIN` | Trigger a clearing cycle for a rail; responds with a `ClearingCycleResult` holding one batch per currency (#11974) |
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
| `amount` | number (BigDecimal) | yes | must be `> 0` (DB CHECK); at most the currency's minor-unit decimals, never rounded (#11604) |
| `currency` | string (CHAR(3)) | no | default `EUR`; any ISO 4217 code with a minor unit, case-insensitive (`eur` is stored as `EUR`) |
| `rail` | enum | **yes** | no default (#12004): an absent `rail` is a 400 and no item is written — a default would clear the payment on a rail its caller did not choose |
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
- `400 Bad Request` on submit — the `amount` + `currency` pair cannot be a kernel `Money` (#11604). It is refused at
  the boundary, before the `paymentId` (the submit's idempotency key, ADR-0298) is looked up, so no clearing item is
  created and a corrected retry with the same `paymentId` succeeds. The body is the platform RFC 9457 `ProblemDetail`
  with a `violations[]` entry naming the field; the rejected value is never echoed:
  - `AMOUNT_SCALE_EXCEEDED` — more decimals than the currency allows (e.g. `100.505 EUR`, `1000.5 JPY`, `1.2345 KWD`);
  - `CURRENCY_UNSUPPORTED` — not an ISO 4217 code with a minor unit (e.g. `XYZ`, `EURO`, `XAU`, blank);
  - `VALIDATION_ERROR` — amount absent or out of range (more than 19 integer digits).
  The `POST` response carries the amount at the currency's scale (`100.50` for a request of `100.5`); reads come
  from the `NUMERIC(20,4)` column as before.
- `500` with body `{ "error": "<message>" }` — failures on `submit`, `settle`, `triggerCycle` are recovered into a server-error response carrying the exception message (`onFailure().recoverWithItem`). A typed RFC-7807 problem+json model is **not yet** in place here.

## Versioning

- **API contract version:** `openapi.yaml: info.version = 1.0.0`; URL major `/api/v1` == contract major (ADR-0048).
- **Release version:** `version.txt = 0.2.0` (independent axis, owned by release-please).
- `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.
