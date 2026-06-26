# openbank-anacredit-service — agent notes

AnaCredit granular credit-exposure reporting (**ADR-0037**), port **8137**. Hexagonal per ADR-0002;
the `domain` package has **zero** framework imports. **Derive-only**: it renders the regulatory
credit dataset, it **moves no money and emits no events**, so it stays **off the money-path gate**
(no threat model / 2-approval requirement under ADR-0030).

## What it does

Builds the **AnaCredit credit dataset** (Reg. (EU) 2016/867, ČNB-collected) for a monthly reference
date from credit exposures — **overdrafts first**. Two regulatory rules carry the logic:

1. **Scope: legal entities only.** A debtor that is a natural person (household/consumer) is out of
   scope → excluded with reason `HOUSEHOLD_OUT_OF_SCOPE`.
2. **Materiality: €25 000 per-debtor commitment threshold.** Evaluated on the debtor's *total*
   commitment across all instruments (not per instrument). Below → `BELOW_THRESHOLD`. An instrument
   with neither commitment nor drawing → `NO_EXPOSURE`.

Reportable instruments map to the credit/financial dataset row:
`outstandingNominalAmount = drawn`, `offBalanceSheetAmount = max(committed − drawn, 0)` (undrawn
commitment), `arrearsAmount`, `defaultStatus`. The return carries both the rows and an **exclusion
audit trail** explaining every dropped instrument.

## Layout

- `domain/model/CreditExposure.kt` — the instrument as the feed sees it (+ `offBalanceSheetAmount`).
- `domain/eligibility/AnaCreditEligibilityPolicy.kt` — pure scope + threshold gate.
- `domain/report/` — `AnaCreditCreditRecord`/`AnaCreditReturn`, `AnaCreditReturnBuilder` (groups by
  debtor, aggregates EUR commitment, applies the policy, maps) + `AnaCreditMapper`.
- `application/` — `AnaCreditService` + in/out ports.
- `infrastructure/persistence/InMemoryCreditExposureRepository.kt` — v1 in-memory store
  (the `openbank-product-catalog` pattern).
- `infrastructure/rest/AnaCreditResource.kt` — `POST /api/v1/anacredit/exposures` (upsert),
  `GET /api/v1/anacredit/exposures`, `GET /api/v1/anacredit/returns/{referenceDate}` (render).

## Build / test

```
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-anacredit-service:test --offline
```
The domain tests are pure JUnit (fast, no boot); `AnaCreditResourceTest` is a `@QuarkusTest`;
`AnaCreditContractTest` pins `openapi.yaml info.version` to `version.txt`.

## v1 non-goals (documented in ADR-0037)

- **No ČNB submission transport** (SDMX/statistical-reporting channel) — renders only.
- **No counterparty reference dataset** golden-sourcing; **no quarterly accounting dataset**.
- Exposures are **fed in via REST** (v1) rather than consumed from `balance.overdraft.*` events;
  event ingestion + persistence is a mechanical follow-up that does not touch the domain.
- **EUR-equivalent commitment is supplied** (`committedAmountEur`) — FX conversion for the threshold
  is the caller's responsibility (`openbank-fx-service`).
