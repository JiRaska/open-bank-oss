# API

REST contract for `openbank-sepa-instant`. The formal contract lives in [`openapi.yaml`](../openapi.yaml) (`info.version 1.1.0`, OpenAPI 3.1.0). The URL major version is `/api/v{N}` with `openbank.api.version = "1"` (ADR-0048). Swagger UI is served at `/api/docs`.

> Note: the source `openapi.yaml` and the deployed `SctInstResource` have minor drift (see the "Drift" note at the bottom). The endpoints below are documented from the **actual resource code**, which is authoritative for behaviour.

## Base path

`/api/v1/sepa-instant`

## Endpoints

### `POST /api/v1/sepa-instant` — Submit a payment

Submits an SCT Inst payment. Synchronously screens debtor and creditor names (ADR-0032) before releasing.

- **Headers:** `Idempotency-Key` (optional; falls back to the body `idempotencyKey` field).
- **Request body** (`SubmitSctInstRequest`):

  | Field | Type | Notes |
  |---|---|---|
  | `idempotencyKey` | string | required (header or body) |
  | `debtorAccountId` | uuid | required |
  | `debtorIban` | string | required |
  | `debtorName` | string | required — screened |
  | `creditorIban` | string | required |
  | `creditorName` | string | required — screened |
  | `creditorBic` | string? | optional |
  | `amount` | number | required |
  | `currency` | string | default `EUR` |
  | `remittanceInfo` | string? | optional |
  | `endToEndId` | string | required |

- **Response:** `201 Created` with `SctInstPaymentResponse`. The `status` reflects the screening outcome: `PROCESSING` (CLEAR), `PENDING` (REVIEW / screening outage), or `REJECTED` (BLOCK / sanctions hit).

### `GET /api/v1/sepa-instant` — List all payments

Returns all payments as `SctInstPaymentResponse[]`. `200 OK`.

### `GET /api/v1/sepa-instant/{paymentId}` — Get by id

`paymentId` is a UUID. `200 OK` with `SctInstPaymentResponse`, or `404` if not found.

### `GET /api/v1/sepa-instant/debtor/{debtorAccountId}` — List by debtor

Query params: `page` (default `0`), `size` (default `20`). Returns `SctInstPaymentResponse[]`. `200 OK`.

### `POST /api/v1/sepa-instant/{paymentId}/recall` — Recall

Recalls a **SETTLED** payment. Protected by `@Authorize(action = "sctInstPayment.recall", resource = "#paymentId")` (ADR-0034 OPA).

- **Request body** (`RecallRequest`): `{ "reason": "FRAUD" | "DUPLICATE" | "WRONG_AMOUNT" | "WRONG_BENEFICIARY" }`.
- **Response:** `200 OK` with the updated `SctInstPaymentResponse` (`status = RECALLED`), emits `SctInstPaymentRecalled`.
- **Errors:** `400` if the payment is not in `SETTLED` state; `404` if not found.

## Response shape — `SctInstPaymentResponse`

| Field | Type |
|---|---|
| `paymentId` | uuid |
| `status` | string (`PENDING`/`PROCESSING`/`SETTLED`/`REJECTED`/`TIMEOUT`/`RECALLED`) |
| `debtorIban` | string |
| `creditorIban` | string |
| `amount` | number |
| `currency` | string |
| `endToEndId` | string |
| `executionTimeoutAt` | date-time? |
| `settledAt` | date-time? |
| `createdAt` | date-time |

## Idempotency

The `Idempotency-Key` (header, or `idempotencyKey` in the body) is enforced by a **unique constraint** on `sct_inst_payments.idempotency_key`. On a repeat submit with the same key, the service returns the already-persisted payment unchanged — no second screening, no duplicate event.

## Error model

Errors are returned as a small JSON object via the exception mappers:

```json
{ "error": "Only SETTLED payments can be recalled" }
```

- `404 Not Found` — unknown `paymentId` (`NotFoundMapper`).
- `400 Bad Request` — invalid state transition, e.g. recalling a non-settled payment (`BadRequestMapper`).

(The `openapi.yaml` declares an `ApiError { code, message }` schema; the deployed mappers emit `{ error }`. Treat the resource behaviour as authoritative.)

## Versioning

- **URL/API contract version:** `/api/v1`, `openapi.yaml info.version = 1.1.0` (API-contract axis, ADR-0048).
- **Release version:** `version.txt = 0.2.0` (independent release axis, owned by release-please).
- `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.

## Drift note

The checked-in `openapi.yaml` lists the submit body without `idempotencyKey`/`debtorIban`/`debtorName`, uses a different status enum (`SUBMITTED/ACCEPTED/SETTLED/REJECTED/RECALLED`), declares the debtor-list pagination as `limit`/`offset`, and a local server port `8111`. The running service uses `page`/`size`, the `PENDING/PROCESSING/SETTLED/REJECTED/TIMEOUT/RECALLED` enum, and port `8127`. Reconciling the contract to the implementation is a TBD follow-up.
