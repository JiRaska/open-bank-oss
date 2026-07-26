# API & contracts

The REST contract is formalized in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.0.3, `info.version` 0.1.1). A contract test (`AnaCreditContractTest`) pins `openapi.yaml:info.version` against `version.txt`.

## Base path

- **Production base:** `http://openbank-anacredit-service:8137/api/v1` (in-cluster)
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (configured via `quarkus.swagger-ui.path`)

## Authentication

All endpoints require a **Keycloak Bearer token** (realm `openbank`). The resource is class-level role-gated:

```
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_COMPLIANCE", "ROLE_API")
```

| Role | Typical use |
|---|---|
| `ROLE_OPERATOR` | register exposures, render returns |
| `ROLE_API` | upstream feed pushing exposures |
| `ROLE_AUDITOR` | read returns + exclusion trail |
| `ROLE_COMPLIANCE` | regulatory review |
| `ROLE_ADMIN` | everything |

There is no per-endpoint method gating in v1 — any of the listed roles can call any endpoint.

## Idempotence

No `Idempotency-Key` header is used. Registration is an **upsert keyed by `instrumentId`** — re-submitting the same instrument replaces its prior snapshot, so the operation is naturally idempotent. The two GET endpoints are pure reads.

## Endpoints

### Register / replace a credit exposure

```http
POST /api/v1/anacredit/exposures
Content-Type: application/json
Authorization: Bearer <token>

{
  "instrumentId": "od-0001",
  "debtorId": "lei-5493001KJTIIGC8Y1R12",
  "debtorType": "LEGAL_ENTITY",
  "instrumentType": "OVERDRAFT",
  "currency": "EUR",
  "committedAmount": 50000.00,
  "drawnAmount": 12000.00,
  "committedAmountEur": 50000.00,
  "arrearsAmount": 0,
  "defaulted": false,
  "originationDate": "2025-11-01"
}
```

```http
201 Created
Content-Type: application/json

{
  "instrumentId": "od-0001",
  "debtorId": "lei-5493001KJTIIGC8Y1R12",
  "debtorType": "LEGAL_ENTITY",
  "instrumentType": "OVERDRAFT",
  "currency": "EUR",
  "committedAmount": 50000.00,
  "drawnAmount": 12000.00,
  "offBalanceSheetAmount": 38000.00
}
```

- `instrumentType` defaults to `OVERDRAFT` if omitted.
- `committedAmountEur` is used **only** for the €25 000 threshold; the dataset reports native `currency` amounts.
- `offBalanceSheetAmount` in the response is derived: `max(committedAmount − drawnAmount, 0)`.

### List all known exposures

```http
GET /api/v1/anacredit/exposures
Authorization: Bearer <token>
```

```http
200 OK
[ { "instrumentId": "od-0001", ... } ]
```

Returns every stored exposure (sorted by `instrumentId`), each as the `Exposure` projection (including the derived `offBalanceSheetAmount`).

### Render the AnaCredit return

```http
GET /api/v1/anacredit/returns/2026-05-31
Authorization: Bearer <token>
```

```http
200 OK
{
  "referenceDate": "2026-05-31",
  "reportableCount": 1,
  "excludedCount": 2,
  "records": [
    {
      "instrumentId": "od-0001",
      "debtorId": "lei-5493001KJTIIGC8Y1R12",
      "instrumentType": "OVERDRAFT",
      "currency": "EUR",
      "outstandingNominalAmount": 12000.00,
      "offBalanceSheetAmount": 38000.00,
      "arrearsAmount": 0,
      "defaultStatus": "NOT_IN_DEFAULT",
      "referenceDate": "2026-05-31"
    }
  ],
  "exclusions": [
    { "instrumentId": "od-0002", "debtorId": "person-1", "reason": "HOUSEHOLD_OUT_OF_SCOPE" },
    { "instrumentId": "od-0003", "debtorId": "lei-small",  "reason": "BELOW_THRESHOLD" }
  ]
}
```

- `referenceDate` is the month-end the return is rendered as of (ISO `yyyy-MM-dd`, path parameter).
- The return always carries **both** the reportable `records` and the `exclusions` audit trail.
- Exclusion `reason` codes: `HOUSEHOLD_OUT_OF_SCOPE`, `BELOW_THRESHOLD`, `NO_EXPOSURE`.

## Schemas (from openapi.yaml)

| Schema | Role |
|---|---|
| `RegisterExposureRequest` | POST body — required: `instrumentId`, `debtorId`, `debtorType`, `currency`, `committedAmount`, `drawnAmount`, `committedAmountEur`, `originationDate` |
| `Exposure` | exposure projection with derived `offBalanceSheetAmount` |
| `CreditRecord` | one reportable row of the credit/financial dataset |
| `Exclusion` | a dropped instrument with its reason code |
| `AnaCreditReturn` | `referenceDate`, `reportableCount`, `excludedCount`, `records[]`, `exclusions[]` |
| `CounterpartyType` | enum `LEGAL_ENTITY`, `NATURAL_PERSON` |
| `InstrumentType` | enum `OVERDRAFT`, `CREDIT_CARD_CREDIT`, `REVOLVING_CREDIT`, `LOAN` |

## Error model

Standard Quarkus / RESTEasy Reactive responses. A malformed `referenceDate` fails `LocalDate.parse` (400-class); auth failures return 401/403 via the security layer. v1 does not define a custom `ApiError` body for this service.

| HTTP | When |
|---|---|
| 400 | unparseable `referenceDate`, malformed JSON / enum |
| 401 | missing / invalid bearer token |
| 403 | token lacks all of the allowed roles |
| 201 | exposure stored |
| 200 | list / return rendered |

## Events

**None.** anacredit-service is derive-only: it emits no domain events and consumes none. There is no Kafka topic and no outbox.

## Backward compatibility

- **API version in URL** (`/api/v1/...`). Breaking changes ⇒ `/api/v2/...`.
- **Two version axes (ADR-0048):** the release version (`version.txt`) and the OpenAPI contract version (`openapi.yaml:info.version`) are independent. `AnaCreditContractTest` enforces that the contract version is kept in sync per the contract-axis rule.
- **OpenAPI diff** in CI against `main` — a removed endpoint or a newly-required field must come with the appropriate contract bump.
