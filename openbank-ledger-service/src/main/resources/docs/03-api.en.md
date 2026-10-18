# API

REST contract served at `/api/v1/journals` and `/api/v1/ledger/fx-revaluation`. The OpenAPI document is bundled at `src/main/resources/openapi.yaml` (`info.version: 1.1.0`) and exposed at `/q/openapi` (Swagger UI at `/q/swagger-ui` in dev).

> **Contract note:** the bundled `openapi.yaml` currently documents only the read endpoints (`GET /journals`, `GET /journals/{journalId}`, `GET /journals/sub-ledger-balances`, `GET /journals/transaction/{transactionId}`). The `GET /journals/trial-balance`, `POST /journals`, `POST /journals/{journalId}/reverse` and `POST /ledger/fx-revaluation` endpoints exist in `LedgerResource` / `FxRevaluationResource` but are **not yet reflected in the OpenAPI file** — closing that gap is a tracked follow-up (the resource classes are the source of truth below).

Regulatory period reads under `/api/v1/ledger/periods/MONTH/{date}` distinguish balances from flows. `frozen-closing-balance` and `closing-balance` provide cumulative stock for F01/COREP. `frozen-year-to-date-trial-balance` provides January-through-report-month F02 flow only after every monthly close is attested and reconciled to the same-year journal; it rejects dates before month end. `year-to-date-trial-balance` provides a mutable working preview through the exact date. These routes do not substitute for a monthly movement read or for each other's evidence level.

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

## Day-close participants (ADR-0342)

`GET /api/v1/ledger/accounting-days/{date}/participants` returns the day's completeness. A declared participant (for example `interest`) reports `RAN`, `SKIPPED` or `REFUSED` with `PUT .../participants/{participant}`; only the service-account declared for it may report. A participant that never reported is listed in `missing`; one whose latest report is `REFUSED` is listed in `refused` - its work (for example interest capitalization) was not posted. Either holds the day in `CUTOFF` (`complete=false`) until the participant reports `RAN` or an operator waives it.

`PUT .../participants/{participant}/waiver` (operator, mandatory reason) is allowed only for an existing `CUTOFF` day and only for a participant with no `RAN` report: `404` for a day that was never opened (so also any future day), `409` for a day that is not in `CUTOFF` or a participant that already ran. A waiver is appended, never overwriting an earlier row; it may supersede a `REFUSED` report.

`openbank.ledger.close.participants-mode` is `off`, `shadow` (default, counts would-hold decisions and still ties out) or `enforce`; any other value fails the service start.

In `enforce`, the accounting-day service applies the participant check to both scheduler and manual operator `CUTOFF → TIED_OUT` transitions, under the existing transactional day lock. A missing or REFUSED participant returns 409 until a valid report or an explicit reasoned operator waiver is recorded. A caller cannot omit this policy. The default remains `shadow`; this change does not authorize enabling enforcement.

A waiver requires a verified human JWT even when OPA authorization is advisory; a machine token is rejected with 403 despite an operator role. Audit attribution uses only the nonblank JWT `sub`; a missing, blank or unreadable subject is 401, without falling back to a display name.
