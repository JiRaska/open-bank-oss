# API

The REST contract is defined in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1, `info.version` 1.1.0) and served from the `TransactionResource`. All paths are under `/api/v1` (`openbank.api.version = 1`, ADR-0048). `X-API-Version` / `X-Service-Version` and `/api/v1/info` are served by `openbank-libs`.

> Note: the `openapi.yaml` `servers` entry lists port 8101 for local dev, but the running application binds **8102** (`application.yaml`). The POST initiate endpoint is implemented in `TransactionResource` but is not yet enumerated in `openapi.yaml` — treat the contract for `POST /api/v1/transactions` as documented here until the spec is regenerated (TBD).

## Endpoints

| Method | Path | Auth (roles) | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/transactions?accountId&limit&cursor` | SERVICE, VIEWER, OPERATOR, ADMIN | List an account's transactions, cursor-paginated |
| `GET` | `/api/v1/transactions/search` | SERVICE, VIEWER, OPERATOR, ADMIN | BIAN-aligned search (IBAN/BBAN/reference/counterparty/amount/date/…) |
| `GET` | `/api/v1/transactions/{transactionId}` | SERVICE, VIEWER, OPERATOR, ADMIN | Get one transaction by id |
| `POST` | `/api/v1/transactions` | OPERATOR | Initiate a transaction (drives the payment saga) |

All reads are authenticated — there is **no `@PermitAll` endpoint** (K7 / ADR-0018): transaction history is customer financial data and the search endpoint queries by IBAN/amount/counterparty, so it is gated to service callers plus viewers/operators/admins. Enforcement is locked by `TransactionSecurityContractTest`.

## List — `GET /api/v1/transactions`

Query params: `accountId` (uuid, required), `limit` (default 20), `cursor` (opaque, base64-encoded last id).

Response `200` — `TransactionPage`:

```json
{
  "data": [ { "...": "TransactionResponse" } ],
  "pagination": { "nextCursor": "…", "hasMore": true }
}
```

Cursor pagination uses `libs.api.pagination.CursorEncoder`; the service fetches `limit + 1` rows to compute `hasMore` and emits `nextCursor` only when more pages exist.

## Search — `GET /api/v1/transactions/search`

Optional filters: `accountId`, `iban`, `bban`, `referenceNumber`, `endToEndId`, `counterparty`, `status` (`PENDING|COMPLETED|REVERSED|FAILED`), `type` (`CREDIT|DEBIT|REVERSAL|FEE`), `dateFrom`, `dateTo` (ISO date), `amountMin`, `amountMax`, `channel`, `limit` (default 50, **coerced to 1..200**), `offset` (default 0, coerced ≥ 0).

Response `200` — `TransactionSearchResult`:

```json
{ "data": [ … ], "count": 12, "limit": 50, "offset": 0 }
```

Unparseable `status`/`type` values are ignored (treated as no filter) rather than rejected.

## Get — `GET /api/v1/transactions/{transactionId}`

Response `200` — `TransactionResponse`; `404` (`ApiError`) when not found (mapped from `TransactionNotFoundException`).

## Initiate — `POST /api/v1/transactions`

Request body (`InitiateTransactionRequest`):

```json
{
  "idempotencyKey": "string (required)",
  "type": "DEBIT|CREDIT|TRANSFER|FEE|INTEREST|REVERSAL|ADJUSTMENT",
  "sourceAccountId": "uuid | null",
  "targetAccountId": "uuid | null",
  "amount": 100.00,
  "currencyCode": "CZK",
  "baseCurrencyCode": "EUR | null",
  "description": "string | null",
  "valueDate": "2026-06-09"
}
```

On success → `201 Created` with `Location: /api/v1/transactions/{id}` and a `TransactionResponse` body. The call runs the payment saga synchronously, so the returned `status` is already terminal (`COMPLETED` or `FAILED`).

### TransactionResponse

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | |
| `referenceNumber` | string | generated `TXN<epochMillis><rand>` |
| `type` | string | TransactionType |
| `sourceAccountId` / `targetAccountId` | uuid? | |
| `amount` | number | |
| `currencyCode` | string | ISO 4217 |
| `status` | string | TransactionStatus |
| `description` | string? | |
| `valueDate` / `bookingDate` | date | resolved by `SettlementDateResolver` |
| `initiatedAt` / `completedAt` | date-time | `completedAt` null until terminal |

## Idempotency

- The caller supplies `idempotencyKey` in the initiate request. A replay returns the **existing** transaction (`findByIdempotencyKey`) — no duplicate booking.
- Enforced at the DB by `uq_transactions_idempotency (idempotency_key, booking_date)` and `uq_payment_sagas_idempotency (idempotency_key)`.
- The downstream ledger post is itself idempotent (key `saga-{id}-ledger`); compensation refunds are tagged `compensation-{txId}`.

## Error model

Errors follow the shared `openbank-libs` `CommonExceptionMappers`:

| Exception | HTTP | Body |
|---|---|---|
| `IllegalArgumentException` (malformed input) | `400` | `VALIDATION_ERROR` |
| `IllegalStateException` (broken invariant, e.g. non-positive amount) | `422` | `BUSINESS_RULE_VIOLATION` |
| `TransactionNotFoundException` | `404` | `{ "error": "…" }` (service-local mapper) |
| `FxRateUnavailableException` | propagated → 5xx | no FX rate for cross-currency settlement |
| any other `Exception` | `500` | correlation-aware `GenericExceptionMapper` |

Domain invariants that must surface as 422 deliberately throw `IllegalStateException` (`check(...)`), never `IllegalArgumentException`, to route through the shared mapper without colliding with a service-local one (ADR-0049 D4).

## Versioning

- **API contract axis:** `openapi.yaml:info.version` (1.1.0); its major == URL `/api/v{N}` == `openbank.api.version` (1). An API change classifies its own bump from the OpenAPI diff (ADR-0048).
- **Release axis:** `version.txt` (1.2.1), owned by release-please — independent of the API axis.
