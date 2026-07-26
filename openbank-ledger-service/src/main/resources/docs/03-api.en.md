# API

REST contract served at `/api/v1/journals` and `/api/v1/ledger/fx-revaluation`. The OpenAPI document is bundled at `src/main/resources/openapi.yaml` (`info.version: 1.1.0`) and exposed at `/q/openapi` (Swagger UI at `/q/swagger-ui` in dev).

> **Contract note:** the bundled `openapi.yaml` currently documents only the read endpoints (`GET /journals`, `GET /journals/{journalId}`, `GET /journals/sub-ledger-balances`, `GET /journals/transaction/{transactionId}`). The `GET /journals/trial-balance`, `POST /journals`, `POST /journals/{journalId}/reverse` and `POST /ledger/fx-revaluation` endpoints exist in `LedgerResource` / `FxRevaluationResource` but are **not yet reflected in the OpenAPI file** — closing that gap is a tracked follow-up (the resource classes are the source of truth below).

## Versioning (ADR-0048)

- URL prefix `/api/v1` — `v{major}` == `openbank.api.version` (`"1"`) == `openapi.yaml:info.version` major.
- The **release** version (`version.txt` = `1.2.0`) is an independent axis from the **API contract** version (`openapi.yaml:info.version` = `1.1.0`). Do not force them equal.
- `X-API-Version` / `X-Service-Version` response headers and `/api/v1/info` are served by `openbank-libs`.

## Authentication & authorization

Keycloak OIDC, RS256 bearer JWT. No endpoint is `@PermitAll` — the general ledger is the book of record (ADR-0018). Roles from `libs.security.Roles`:

| Operation | Required role(s) |
|---|---|
| All reads (list/get/trial-balance/sub-ledger/by-transaction) | `ROLE_API`, `ROLE_AUDITOR`, `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /journals` (post) | `ROLE_OPERATOR` |
| `POST /journals/{id}/reverse` | `ROLE_OPERATOR` |
| `POST /ledger/fx-revaluation` | `ROLE_OPERATOR` |

The role matrix is locked by `LedgerSecurityContractTest`.

## Endpoints

### `GET /api/v1/journals` — list journal entries (cursor-paginated)

Query params: `fromDate` (default `2020-01-01`), `toDate` (default today), `limit` (default 20), `cursor`.
Returns a `CursorPage<JournalEntryResponse>`: `{ data: [...], pagination: { nextCursor, hasMore } }`.

### `GET /api/v1/journals/{journalId}` — get one entry

`404` (ApiError) if not found.

### `GET /api/v1/journals/transaction/{transactionId}` — entries for a transaction

Returns an array of `JournalEntryResponse` (a transaction may have multiple postings, e.g. original + reversal).

### `GET /api/v1/journals/trial-balance` — trial balance

Query param: `asOf` (default today). Returns debit/credit totals per GL account plus an overall `balanced` flag (must net to zero). Used for SOX/DORA evidence and end-of-day reconciliation.

### `GET /api/v1/journals/sub-ledger-balances` — per-customer sub-ledger (ADR-0039 Phase B)

Query params: `asOf` (default today), `subAccountId` (optional filter). Aggregates POSTED journal lines that carry a `sub_account_id`, grouped by `(subAccountId, currency)`, with `totalDebit`, `totalCredit`, and `net` (credit − debit; deposit-control is credit-normal). Ties the GL deposit-control account out against the per-account read-model (CNB 563/1991 + 501/2002).

### `POST /api/v1/journals` — post a balanced journal entry

Body (`PostJournalRequest`):

```json
{
  "idempotencyKey": "txn-9f2c-post",
  "transactionId": "…uuid…",
  "entryDate": "2026-06-09",
  "valueDate": "2026-06-09",
  "description": "Customer transfer settlement",
  "createdBy": "…operator uuid…",
  "lines": [
    { "glAccountId": "…", "side": "DEBIT",  "amount": 100.00, "currencyCode": "CZK",
      "baseAmount": 100.00, "baseCurrencyCode": "CZK", "subAccountId": "…", "fxRate": null },
    { "glAccountId": "…", "side": "CREDIT", "amount": 100.00, "currencyCode": "CZK",
      "baseAmount": 100.00, "baseCurrencyCode": "CZK" }
  ]
}
```

- **Must contain ≥ 2 lines and balance within each base currency** — otherwise the aggregate `init` rejects it (mapped to a `400`-class error).
- `201 Created` with `Location: /api/v1/journals/{id}` and the `JournalEntryResponse` body.

### `POST /api/v1/journals/{journalId}/reverse` — reverse a posted entry

Body (`ReverseJournalRequest`): `{ "reason": "...", "reversedBy": "…uuid…" }`. Creates a new balanced entry with sides flipped, linked back via `reversal_of`. Only `POSTED` entries are reversible. Returns `200` with the reversal `JournalEntryResponse`.

### `POST /api/v1/ledger/fx-revaluation` — daily FX revaluation (ADR-0046)

Query param: `date` (default today, Europe/Prague). Re-runs the daily mark-to-ČNB revaluation for that business day. **Idempotent** — exactly one entry per day (`idempotencyKey = fx-reval-{date}`); a same-day re-run is a no-op. Returns the `FxRevaluationResult` (`posted`, `journalId`, per-currency `movements`).

## Idempotency

Posting is idempotent via the **`idempotencyKey` field on the request body** (not an HTTP header). A key maps to exactly one journal entry through the `ledger_idempotency` table; a replay with the same key returns the original entry instead of double-posting. This is the money-path safety net for at-least-once upstream retries.

## Error model

`ApiError`: `{ "code": "...", "message": "..." }`. Mapping is centralized in `ExceptionMappers` and deferred to `openbank-libs` for the generic fallback (ADR-0049 D4 — the service does not register a catch-all `Exception` mapper). Typical statuses: `400` (unbalanced / invalid posting), `401`/`403` (auth), `404` (unknown journal), `409` (idempotency-key conflict).
