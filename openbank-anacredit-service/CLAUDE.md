# openbank-anacredit-service — agent notes

AnaCredit granular credit-exposure reporting (**ADR-0037**), port **8137**. Hexagonal per ADR-0002;
the `domain` package has **zero** framework imports. **Derive-only**: it renders the regulatory
credit dataset and **moves no money and emits no events**, so it stays **off the money-path gate**
(no threat model / 2-approval requirement under ADR-0030) — it now **consumes** one event
(`lending-service`'s `loan.stage_changed`, issue #638) but that is a one-way read, not a return path.

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
  (the `openbank-product-catalog` pattern). **Still in-memory** — this increment does not change it.
- `infrastructure/rest/AnaCreditResource.kt` — `POST /api/v1/anacredit/exposures` (upsert),
  `GET /api/v1/anacredit/exposures`, `GET /api/v1/anacredit/returns/{referenceDate}` (render).
- `domain/model/LoanStageProjection.kt` + `application/port/out/LoanStageProjectionRepository.kt` +
  `infrastructure/persistence/{entity,repository}/LoanStageProjection*` — the durable "last known IFRS
  9 stage per loan" projection (issue #638). **The one piece of real Postgres persistence in this
  service** — narrowly scoped to this table; `CreditExposure` itself is untouched and still in-memory.
- `infrastructure/kafka/LoanStageEventConsumer.kt` — `@Incoming("lending-events-in")`, consumes
  `openbank-lending-service`'s `loan.stage_changed` (topic `openbank.lending.events`) and applies it to
  the projection via `LoanStageProjectionRepository.applyIfNewer` (idempotent, keyed on loanId +
  strictly-newer `eventTimestamp` — an out-of-order or duplicate event can never regress the stage).
  Same shape as `kyc-service`/`aml-service`'s `PartyEventConsumer`.

## Build / test

```
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-anacredit-service:test --offline
```
The domain tests are pure JUnit (fast, no boot); `AnaCreditResourceTest`/`AnaCreditSecurityTest` are
`@QuarkusTest`s that do **not** need Postgres/Kafka (in-memory `CreditExposure` feed only);
`AnaCreditContractTest` pins `openapi.yaml info.version` to `version.txt` (unaffected by this
increment — no REST API surface change). `AnaCreditBootSmokeIT` and `LoanStageProjectionRepositoryIT`
DO need real infra and use `PostgresRedpandaTestResource` (Testcontainers Postgres + Redpanda,
mirrors `balance-service`/`party-service`) — `quarkus.devservices.enabled` stays `false` in `%test`
for the fast unit/contract tests; the two Testcontainers-backed classes supply their own connection
properties directly via `QuarkusTestResourceLifecycleManager`, bypassing Dev Services entirely.

## v1 non-goals (documented in ADR-0037)

- **No ČNB submission transport** (SDMX/statistical-reporting channel) — renders only.
- **No counterparty reference dataset** golden-sourcing; **no quarterly accounting dataset**.
- The **`CreditExposure` feed itself is still fed in via REST** and stays in-memory — this increment
  adds a *separate*, narrowly-scoped persisted projection (`loan_stage_projection`) for lending's IFRS 9
  stage, not a re-platform of the exposure store. Wiring that projection into
  `AnaCreditReturnBuilder`/`CreditExposure` (so a `LOAN`-instrument-type row actually reflects real
  overdue/stage data in the rendered return) is a separate, later increment — issue #638 lands the
  durable, correctly-ordered projection first.
- **EUR-equivalent commitment is supplied** (`committedAmountEur`) — FX conversion for the threshold
  is the caller's responsibility (`openbank-fx-service`).
