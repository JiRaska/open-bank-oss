# API

The REST contract is formalized in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version 0.1.0`). Swagger UI is served at `/api/docs`. All paths are versioned under `/api/v1` (ADR-0048: the OpenAPI `major` == `openbank.api.version` == URL `/api/v{N}`).

Base path: `/api/v1/lending`. All endpoints require a Keycloak bearer JWT (`bearerAuth`).

## Authorization

The resource class is role-gated; the **acting principal is always the authenticated JWT subject** (`SecurityIdentity.principal.name`), never a client-supplied field. Class-level roles: `ROLE_LENDING_OFFICER`, `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN`. Per-endpoint overrides tighten this:

| Endpoint | Method | Roles | Notes |
|---|---|---|---|
| `/applications` | `POST` | (class roles) | Submit application (maker). 201 / 400 |
| `/applications` | `GET` | (class roles) | List applications by `partyId` (required query) |
| `/applications/{id}` | `GET` | (class roles) | 200 / 404 |
| `/applications/{id}/decision` | `POST` | `ROLE_CREDIT_RISK`, `ROLE_ADMIN` | Approve/reject (checker). Checker must differ from maker. 200 / 409 |
| `/applications/{id}/disburse` | `POST` | `ROLE_LENDING_OFFICER`, `ROLE_ADMIN` | Disburse approved loan. Disburser must differ from checker. 201 / 409 |
| `/loans` | `GET` | (class roles) | List loans by `partyId` (required query) |
| `/loans/{id}` | `GET` | (class roles) | 200 / 404 |
| `/loans/{id}/schedule` | `GET` | (class roles) | Repayment schedule |
| `/loans/{id}/installments/{installmentId}/repay` | `POST` | (class roles) | Record repayment. 200 / 409 |
| `/loans/{id}/writeoff` | `POST` | `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN` | Write off remaining exposure. 200 / 409 |
| `/loans/{id}/collateral` | `POST` | (class roles) | Register collateral. 201 / 400 |
| `/loans/{id}/collateral` | `GET` | (class roles) | List collateral |
| `/loans/{id}/provisioning` | `GET` | `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN` | IFRS 9 staging + ECL. Optional `asOf` (date). 200 / 404 |

The scheduled monthly IFRS 9 provisioning cycle (ADR-0028 Phase 3, `ProvisioningCycleScheduler`) is **not** REST-triggered in this increment — it runs only on `lending.provisioning.cycle.every`. `GET /loans/{id}/provisioning` remains the on-demand, non-persisted read; the persisted per-period history it does not yet expose lives in `loan_provisioning` (no read endpoint over it yet — a natural small follow-up).

## Four-eyes / segregation of duties

Origination is a maker-checker-disburser chain enforced server-side (ADR-0028 D5, EBA/GL/2020/06):

```
maker (POST /applications)           → application PROPOSED, proposed_by = JWT subject
checker (POST .../decision)          → APPROVED/REJECTED, decided_by = JWT subject
                                        409 if decided_by == proposed_by  (four-eyes violation)
disburser (POST .../disburse)        → DISBURSED + loan booked
                                        409 if disburser == decided_by    (segregation of duties)
```

A decision is only accepted on a `PROPOSED` application; disbursement only on an `APPROVED` one; otherwise `409`.

## Request schemas (selected)

- **LoanApplicationRequest** — `partyId` (uuid), `requestedAmount` (Money), `nominalAnnualRate` (number), `termPeriods` (int), `periodsPerYear` (int, default 12), `method` (`ANNUITY`|`EQUAL_PRINCIPAL`|`BULLET`, default ANNUITY), `firstDueDate` (date). **No `proposedBy`** — the maker is the JWT subject.
- **DecisionRequest** — `approve` (bool, required), `reason` (string, nullable). **No `decidedBy`** — the checker is the JWT subject.
- **CollateralRequest** — `type` (string), `description` (nullable), `marketValue` (Money), `haircut` (number, default 0, validated to `[0,1]`).
- **WriteOffRequest** — `reason` (string, nullable). The acting principal is the JWT subject.
- **Money** — `{ amount: number, currency: ISO-4217 }`.

Validation (application service): requested amount must be positive, term ≥ 1 period, nominal rate ≥ 0, proposer identity non-blank, haircut within `[0,1]`.

## Idempotency

CORS allows an `Idempotency-Key` header, and the service has a Redis client configured for idempotency plumbing (via libs). At the **ledger boundary**, idempotency is intrinsic: each posting's economic-event reference (e.g. `loan:<id>:disbursement`, `loan:<id>:inst:<n>:accrual`) is used as the ledger `idempotencyKey`, so replays collapse to a single journal. The accrual pass is idempotent via the `interest_accrued` row flag.

## Error model

Errors return `application/json` with shape `{ "error": "<message>" }` (`ApiError`). Status mapping observed in `LendingResource`:

- `400 Bad Request` — validation failures on create (`applyForLoan`, `registerCollateral`).
- `404 Not Found` — unknown application / loan (and on `provisioning` lookup failure).
- `409 Conflict` — illegal state transition or four-eyes / segregation-of-duties violation (decision, disburse, repay, writeoff).
- `201 Created` — application accepted, loan disbursed, collateral registered.
- `200 OK` — reads, decision applied, repayment recorded, write-off, provisioning snapshot.

## Versioning

`/api/v1/...` URL path; `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`. The OpenAPI contract version (`info.version`) is the API-contract axis (ADR-0048), independent of the release `version.txt`.
