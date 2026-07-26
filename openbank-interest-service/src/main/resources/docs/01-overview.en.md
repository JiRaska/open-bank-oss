# Overview

## What the service does

`openbank-interest-service` is the **interest engine** of the OpenBank platform. It holds:

- **InterestRateConfig** — a rate configuration per product: annual rate, rate type (FIXED / VARIABLE / TIERED), day-count convention (ACT_365 / ACT_360 / ACT_ACT / 30_360), balance tiers (`minBalance` / `maxBalance`), and a validity window (`effectiveFrom` / `effectiveTo`).
- **InterestAccrual** — one daily accrual row per `(account, accrualDate, product)`: the balance, the daily rate, and the accrued amount. Status `ACCRUING → CAPITALIZED` (or `REVERSED` / `SUSPENDED`).
- **InterestCapitalization** — the periodic credit of accrued interest, carrying the gross / tax / net split (ADR-0033): the customer is credited the **net** amount.
- **WithholdingTax** — the paired withholding-tax liability per capitalization (treatment, taxable base, rate, tax amount, status `RECORDED → REMITTED → RECONCILED` / `REVERSED`).
- **WithholdingRemittance** — the monthly remittance batch (*Vyúčtování daně vybírané srážkou*, ADR-0038) aggregating all CZK tax withheld in a tax month, owed to the finanční úřad by a due date.

## What the service **does NOT** do

- ❌ Does not hold or compute account balances — `balance-service` is the authoritative balance; interest uses the balance passed in the accrual request.
- ❌ Does not post double-entry ledger entries — `ledger-service` does; `capitalized.ledgerEntryId` is a reference link (nullable in v1).
- ❌ Does not move cash to the customer or to the tax authority — capitalization records a credit; the tax cash leg (odvod) is delegated downstream via the `interest.withholding.remitted.v1` event (ADR-0030 off-gate).
- ❌ Does not resolve the beneficiary's party tax attributes yet — v1 ships a fail-safe CZ-resident-individual default provider; account→party tax resolution is a documented fast-follow.
- ❌ Does not run KYC/AML or sanctions screening.

## Position in the domain

```
   ┌────────────┐  POST /rates, /accrue,        ┌──────────────────┐
   │  admin UI  │  /capitalize, /remittances    │  scheduler (cron)│
   │ / operator │ ───────────────────────────►  │ accrual 01:00    │
   └─────┬──────┘                               │ capitalize 02:00 │
         │                                       └────────┬─────────┘
         ▼                                                ▼
   ┌──────────────────┐  outbox → Kafka   ┌────────────────────────────┐
   │ interest-service │ ────────────────► │ tax / reporting consumer   │
   └────┬─────────────┘                   │ (pays finanční úřad)       │
        │                                 │ audit-service              │
        ▼                                 │ ledger-service (credit GL) │
    PostgreSQL                            └────────────────────────────┘
   (db: openbank_interest)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Create an interest rate config | `POST /api/v1/interest/rates` | — |
| Accrue interest for one account | `POST /api/v1/interest/accrue` | — |
| Accrue interest for all accounts | `POST /api/v1/interest/accrue/all` | — |
| Capitalize accrued interest (apply withholding, credit net) | `POST /api/v1/interest/capitalize/{accountId}` | `interest.withholding.recorded.v1` |
| List accruals / summary for an account | `GET /api/v1/interest/accruals/{accountId}[/summary]` | — |
| List capitalization history | `GET /api/v1/interest/capitalizations/{accountId}` | — |
| Assemble the monthly withholding remittance | `POST /api/v1/interest/withholding/remittances?year=&month=` | `interest.withholding.remitted.v1` |
| Get / list remittance batches | `GET /api/v1/interest/withholding/remittances[/{year}/{month}]` | — |

## Callers

- **admin-ui** (via Keycloak token) — operators configure rates, trigger accrual/capitalization, and assemble remittances.
- **scheduler** (internal Quarkus `@Scheduled`) — daily accrual cron `0 0 1 * * ?` and monthly capitalization cron `0 0 2 1 * ?`.
- **service callers** (`ROLE_API`) — batch / orchestration triggers.

## Dependencies

- **PostgreSQL** (`openbank-postgres`, database `openbank_interest`) — accruals, capitalizations, withholding, remittance, outbox.
- **Kafka** (`openbank-kafka`, topic `openbank.interest.accrual.event`) — outbound withholding events.
- **Redis (Valkey)** — wired as a dependency; no idempotency-key flow used by the resources in v1.
- **Keycloak** — OIDC auth.
- **openbank-libs** — shared runtime plumbing (BuildInfo, ServiceInfoResource, DocsResource, security).
- **TaxProfilePort** — resolves the beneficiary tax profile; v1 default provider returns the fail-safe CZ-resident-individual profile.

## Business value

- **Correct customer interest** — a single tested rate/day-count engine, so accrual logic cannot drift across call sites.
- **Statutory tax at source** — Czech final withholding (§36/§38d ZDP) is applied at capitalization in one tested policy; the customer is credited net and the liability is recorded for the audit trail (ADR-0033).
- **Regulatory remittance** — the monthly *Vyúčtování daně vybírané srážkou* is assembled deterministically and idempotently per tax period, with the cash leg delegated downstream (ADR-0038).
- **Auditability** — every capitalization records the gross/tax/net decision (even zero-tax treatments) and emits a versioned domain event.
