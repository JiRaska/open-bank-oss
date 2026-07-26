# API & contracts

The REST contract is formalized in [`openapi.yaml`](../openapi.yaml) (`info.version: 1.2.0`, OpenAPI 3.1). The major version of the API contract maps to the URL prefix `/api/v1` (ADR-0048 — the API contract version is independent of the service release `version.txt`).

## Base path

- **Production base:** `http://openbank-interest-service:8125/api/v1` (in-cluster)
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (`quarkus.swagger-ui.path`, always included)

> Note: the `servers:` block in `openapi.yaml` lists a local-dev URL on port 8119; the **authoritative app HTTP port is 8125** (`quarkus.http.port` in `application.yaml`), with management on 8085.

## Authentication & authorization

All endpoints require a **Keycloak Bearer token** (realm `openbank`). Roles are enforced with `@RolesAllowed`:

| Operation class | Allowed roles |
|---|---|
| Reads (`GET` accruals, summary, capitalizations, rates, remittances) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` (remittances also `ROLE_AUDITOR`) |
| Mutations (`POST` accrue, capitalize, rates, remittances; `DELETE` rate) | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` |

## Idempotence

- v1 does **not** gate mutations with an `Idempotency-Key` header (no Redis idempotency store is wired into the resources).
- The **remittance assembly is idempotent by construction**: there is one batch per `(year, month)` (DB unique constraint `uq_withholding_remittance_period` on `(period_year, period_month, authority)`). Re-running `POST /withholding/remittances` for an already-assembled period returns the existing batch and re-marks no withholding rows.
- Daily accruals are deduplicated at the data layer by the unique constraint `(account_id, accrual_date, product_id)`.

## Endpoints

### Interest (tag `Interest`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/interest/accrue` | Accrue interest for a single account (body `AccrualRequest`) |
| `POST` | `/api/v1/interest/accrue/all?date=` | Accrue interest for all accounts (v1 returns `{processed: 0}` placeholder) |
| `POST` | `/api/v1/interest/capitalize/{accountId}?productId=&toDate=` | Capitalize accrued interest; applies withholding, credits net |
| `GET` | `/api/v1/interest/accruals` | List all accruals |
| `GET` | `/api/v1/interest/accruals/{accountId}?from=&to=` | Accruals for an account |
| `GET` | `/api/v1/interest/accruals/{accountId}/summary?from=&to=` | Accrual summary (defaults: last month → today) |
| `GET` | `/api/v1/interest/capitalizations/{accountId}` | Capitalization history |
| `POST` | `/api/v1/interest/rates` | Create rate config (body `InterestRateConfig`) |
| `GET` | `/api/v1/interest/rates?productId=` | List rate configs |
| `GET` | `/api/v1/interest/rates/{id}` | Get rate config by id |
| `DELETE` | `/api/v1/interest/rates/{id}` | Deactivate rate config |

### Withholding remittance (tag `Withholding remittance`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/interest/withholding/remittances?year=&month=` | Assemble (or return) the monthly batch; advances `RECORDED → REMITTED` |
| `GET` | `/api/v1/interest/withholding/remittances` | List assembled batches |
| `GET` | `/api/v1/interest/withholding/remittances/{year}/{month}` | Get the batch for a tax period (`404` if none) |

### Capitalize — example

```http
POST /api/v1/interest/capitalize/7f3e2a1b-...?productId=SAVINGS_STD&toDate=2026-05-31
Authorization: Bearer <token>
```

```http
200 OK
Content-Type: application/json

{
  "id": "c0ffee00-...",
  "accountId": "7f3e2a1b-...",
  "productId": "SAVINGS_STD",
  "periodFrom": "2026-05-01",
  "periodTo": "2026-05-31",
  "totalAccrued": 1234.567890,
  "grossAmount": 1234.5679,
  "taxAmount": 185.0000,
  "netAmount": 1049.5679,
  "capitalizedAmount": 1049.5679,
  "currency": "CZK",
  "ledgerEntryId": null,
  "createdAt": "2026-06-01T02:00:01Z"
}
```

The customer is credited the **net** amount. For non-CZK interest no tax is withheld in v1 (`treatment = DEFERRED_FX`), so `net = gross` and `taxAmount = 0`.

### Withholding-tax split (ADR-0033)

| Beneficiary | Treatment | Rate | Effect |
|---|---|---|---|
| CZK, resident individual | `WITHHELD` | 15 % (§36) | net credited, tax recorded |
| CZK, non-resident, non-cooperating/no-treaty state | `WITHHELD` | 35 % (§36/1/c) | net credited |
| CZK, non-resident with treaty rate | `WITHHELD` | treaty rate | net credited |
| CZK, legal entity | `NOT_WITHHELD` | 0 | gross credited (enters CIT base) |
| CZK, statutory/treaty exemption on file | `EXEMPT` | 0 | gross credited, reason recorded |
| Non-CZK interest | `DEFERRED_FX` | 0 | gross credited; withholding deferred |

Taxable base and tax amount are rounded **down to whole CZK** (daňový řád).

## Error model

Resources currently translate domain failures to a generic body:

```json
{ "error": "No active rate config for product SAVINGS_STD" }
```

| HTTP | When |
|---|---|
| 200 | successful read / capitalize / deactivate |
| 201 | rate config created, accrual created, remittance assembled |
| 401 | missing / invalid token |
| 403 | role missing for the endpoint |
| 404 | rate config / remittance not found |
| 500 | domain failure (recovered to `{error: ...}`) — e.g. no active rate config, no pending accruals |

> A unified RFC-9457 problem-detail body (`openbank-libs.api.ApiError`) is the platform direction; this service uses the simpler `{error}` shape in v1.

## Events

Topic: `openbank.interest.accrual.event` (JSON payload; partition key = `aggregate_id`; `ce-id` / `ce-type` / `idempotency-key` headers).

| Event type | Trigger | Payload (key fields) |
|---|---|---|
| `interest.withholding.recorded.v1` | capitalize | `schemaVersion`, `capitalizationId`, `withholdingId`, `accountId`, `productId`, `periodFrom/To`, `currency`, `grossAmount`, `taxableBase`, `rate`, `taxAmount`, `netAmount`, `treatment`, `status` |
| `interest.withholding.remitted.v1` | assemble remittance | `schemaVersion`, `remittanceId`, `periodYear`, `periodMonth`, `authority`, `currency`, `totalTaxAmount`, `itemCount`, `dueDate`, `status` |

Events are **append-only** and carry `schemaVersion`. Schema evolution is additive only.

## Backward compatibility

- **API version in URL** (`/api/v1/...`). The contract major == `openbank.api.version` == URL prefix (ADR-0048). Breaking changes ⇒ `/api/v2`.
- **Event version in the type suffix** (`...v1`) plus an in-payload `schemaVersion`. Additive evolution only.
- **OpenAPI diff** in CI (`oasdiff`) classifies the contract bump independently of the service release.
