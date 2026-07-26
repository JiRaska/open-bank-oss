# Overview

## What the service does

`openbank-anacredit-service` builds the **AnaCredit credit dataset** (Reg. (EU) 2016/867, ECB; collected nationally by the Czech National Bank) for a monthly reference date. It is a **derive-only** reporting projection ([ADR 0037](../../../../docs/adr/0037-anacredit-credit-exposure-reporting.md)) that:

- **Registers credit exposures** — one row per credit instrument as the feed sees it: `instrumentId`, `debtorId`, `debtorType` (LEGAL_ENTITY / NATURAL_PERSON), `instrumentType` (OVERDRAFT / CREDIT_CARD_CREDIT / REVOLVING_CREDIT / LOAN), native `committedAmount` / `drawnAmount`, the EUR-equivalent commitment `committedAmountEur`, `arrearsAmount`, `defaulted`, `originationDate`.
- **Applies the AnaCredit eligibility gate** — two regulatory rules, pure domain logic:
  1. **Scope: legal entities only.** A natural-person debtor (household / consumer) is out of scope → excluded with reason `HOUSEHOLD_OUT_OF_SCOPE`.
  2. **Materiality: €25 000 per-debtor commitment threshold.** Evaluated on the debtor's *total* commitment across all their instruments, not per instrument → below it, `BELOW_THRESHOLD`. An instrument with neither a commitment nor a drawing → `NO_EXPOSURE`.
- **Renders the return** — reportable instruments are mapped to credit/financial dataset rows (`outstandingNominalAmount = drawn`, `offBalanceSheetAmount = max(committed − drawn, 0)`, `arrearsAmount`, `defaultStatus`), and every dropped instrument is recorded in an **exclusion audit trail** with its reason code.

## What the service **does NOT** do

- ❌ Does not move money, post to a ledger, or change any balance — it only reads exposures it was given.
- ❌ Does not emit or consume domain events — there is no outbox and no Kafka binding in v1.
- ❌ Does not submit anything to the ČNB / ECB statistical-reporting channel — **no SDMX transport** in v1; it renders only.
- ❌ Does not own the counterparty reference dataset, nor produce the quarterly accounting dataset (v1 non-goals, ADR-0037).
- ❌ Does not do FX conversion — the caller supplies `committedAmountEur` (sourced from `openbank-fx-service`); native amounts are what the dataset reports.
- ❌ Is not the source of truth for instruments — exposures are fed in via REST (v1); event ingestion from `balance.overdraft.*` is a documented follow-up.

## Position in the domain

```
   ┌────────────┐   POST /exposures (feed)    ┌──────────────────────┐
   │  operator  │ ─────────────────────────►  │                      │
   │ / upstream │   GET /returns/{date}        │  anacredit-service   │
   └────────────┘ ◄─────────────────────────  │  (derive-only)       │
                      rendered return          └──────────┬───────────┘
                                                          │ PostgreSQL (ADR-0037 v2)
   ┌────────────┐                                         ▼
   │ fx-service │ ─ committedAmountEur ─►          credit_exposures
   └────────────┘   (caller-supplied)         (anacredit_schema)

   downstream regulator (ČNB) submission = OUT OF SCOPE
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Register / replace a credit exposure | `POST /api/v1/anacredit/exposures` | — (none) |
| List all known exposures | `GET /api/v1/anacredit/exposures` | — |
| Render the AnaCredit return for a reference date | `GET /api/v1/anacredit/returns/{referenceDate}` | — |

## Callers

- **admin-ui / operators** (via Keycloak token) — compliance and regulatory-reporting staff registering exposures and pulling the monthly return.
- **upstream feed / service accounts** (`ROLE_API`) — a batch or upstream service that pushes overdraft exposures (v1: manual/REST; future: event-driven).
- **auditors** (`ROLE_AUDITOR`) — read the return plus its exclusion trail for evidence.

## Dependencies

- **Keycloak** — OIDC authentication / role enforcement.
- **openbank-libs** — `ServiceInfoResource` (`/api/v1/info`), `DocsResource` (this documentation), `BuildInfo`.
- **PostgreSQL** (`openbank_anacredit`) at runtime for the `credit_exposures` store (ADR-0037 v2). **No** Kafka, **no** Redis.
- `openbank-fx-service` — *logical* dependency only: the caller uses it to obtain `committedAmountEur` before registering an exposure; anacredit-service does not call it.

## Business value

- **Regulatory coverage** — produces the granular AnaCredit credit dataset OpenBank is obliged to report (Reg. (EU) 2016/867), starting with overdraft exposures.
- **Pure, auditable rules** — scope + materiality are a single pure-domain policy with stable, audit-facing exclusion reason codes; every dropped instrument is explained.
- **Cheap to run** — no resident database, no event listeners; the service idles at near-zero cost (FinOps tier T1, see [05 — Operations](./05-operations.md)).
- **Extensible** — loans and credit-card credit (ADR-0028) plug additional instrument types into the same builder without touching the domain rules.
