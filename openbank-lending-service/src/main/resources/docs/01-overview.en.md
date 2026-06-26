# Overview

## What the service does

`openbank-lending-service` is the **lending / credit bounded context** (ADR-0028). It owns the loan book through its full lifecycle:

- **Loan origination** — `LoanApplication` aggregate moving through a four-eyes (maker-checker) decision flow: a maker proposes, a different checker approves/rejects, and a third officer disburses. The maker, checker and disburser identities are taken from the authenticated JWT subject server-side, never from the request body (ADR-0028 D5, EBA/GL/2020/06).
- **Servicing** — the `Loan` aggregate booked from an approved, disbursed application, with its contractual repayment schedule (`LoanInstallment` rows generated from the pure `libs.lending.Amortization` primitive: ANNUITY / EQUAL_PRINCIPAL / BULLET).
- **Accrual-basis interest recognition** — a scheduled servicing pass recognizes each installment's interest as income once it falls due (IAS 1), independent of when cash settles; the `interest_accrued` flag keeps it idempotent.
- **Collateral** — `Collateral` aggregate (AnaCredit protection categories) registered against a loan, with a risk haircut in `[0,1]`.
- **IFRS 9 provisioning** — a point-in-time staging + Expected Credit Loss (ECL) snapshot per loan, computed from the pure `libs.lending.Ifrs9` and `Delinquency` primitives.

## What the service **does NOT** do

- Does not maintain a double-entry book or own balances — it posts balanced journals to `ledger-service` (`POST /api/v1/journals`).
- Does not run credit math itself — all amortization / IFRS 9 / delinquency math lives in `openbank-libs` (`libs.lending`).
- Does not run a credit bureau or PD model — the `CreditBureauPort` / `RiskParameterSource` ports have conservative no-op defaults; a real model is a wiring change.
- Does not move cash or hold accounts — disbursement and repayment are accounting postings, not payment execution.
- Does not run KYC/AML at application time — that is `kyc-service` / `aml-service` (lending events feed the audit/compliance pipeline).

## Position in the domain

```
   ┌────────────┐  POST /applications     ┌──────────────────┐
   │  admin UI  │ ─────────────────────►  │ lending-service  │
   │ (operators)│  decision / disburse    │  (loan book)     │
   └────────────┘                         └────┬─────────────┘
                                               │ ledger posting
                                               │ (POST /api/v1/journals)
                                               ▼
                                         ┌──────────────────┐
                                         │  ledger-service  │
                                         └──────────────────┘
                       outbox → Kafka          │
   ┌──────────────────┐   openbank.lending.events
   │ PostgreSQL        │ ◄── lending-service ──────► ┌────────────────┐
   │ (openbank_lending)│                             │ audit-service  │
   └──────────────────┘                             │ analytics / BI │
                                                     └────────────────┘
```

## Key use cases

| Use case | API | Event / posting |
|---|---|---|
| Submit a loan application (maker) | `POST /api/v1/lending/applications` | — |
| Approve / reject (checker ≠ maker) | `POST /api/v1/lending/applications/{id}/decision` | — |
| Disburse an approved loan (disburser ≠ checker) | `POST /api/v1/lending/applications/{id}/disburse` | event `loan.disbursed`, posting `DISBURSEMENT` |
| Record a repayment | `POST /api/v1/lending/loans/{id}/installments/{instId}/repay` | postings `PRINCIPAL_REPAYMENT` + `INTEREST`/`INTEREST_SETTLEMENT` |
| Accrue due interest (scheduled) | — (servicing loop) | event `loan.interest_accrued`, posting `INTEREST_ACCRUAL` |
| Write off an uncollectible loan | `POST /api/v1/lending/loans/{id}/writeoff` | event `loan.written_off`, posting `WRITE_OFF` |
| Register collateral | `POST /api/v1/lending/loans/{id}/collateral` | — |
| IFRS 9 staging + ECL snapshot | `GET /api/v1/lending/loans/{id}/provisioning` | — |

## Callers

- **admin-ui** (via Keycloak token) — lending officers, credit-risk, compliance operators
- **scheduler (internal)** — the interest-accrual servicing loop runs in-process, not an external caller

## Dependencies

- **PostgreSQL** (`openbank_lending` database; governance schema name `lending_schema`)
- **Kafka** (`openbank-kafka`, topic `openbank.lending.events`)
- **ledger-service** — REST client `POST /api/v1/journals` (build-time gated; no-op offline)
- **Redis (Valkey)** — configured client (rate-limit / cache plumbing via libs)
- **Keycloak** — auth (OIDC + OIDC-client for the service-to-ledger token)
- **openbank-libs** — `Money`, identifiers (`LoanId`, `LoanApplicationId`, `CollateralId`), `libs.lending` (`Amortization`, `Ifrs9`, `Delinquency`), outbox plumbing, BuildInfo, DocsResource

## Business value

- **Single source of truth** for the loan book — origination decisions, schedules, collateral and provisioning in one bounded context.
- **Audit-grade segregation of duties** — four-eyes on the credit decision and segregation of duties on the money-out step, enforced server-side from the JWT subject and unspoofable.
- **Correct accounting by construction** — interest income is recognized exactly once (accrual vs. cash-basis split), and every cash event self-balances as a double-entry journal in the ledger.
- **Regulatory readiness** — IFRS 9 staging/ECL, AnaCredit-aligned collateral categories, and an immutable event trail for audit.
