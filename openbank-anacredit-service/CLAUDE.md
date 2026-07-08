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
- `application/` — `AnaCreditService` + in/out ports (all `suspend` — see below).
- `infrastructure/persistence/` — `CreditExposureEntity` (reactive Panache) +
  `PostgresCreditExposureRepository` (ADR-0037 v2, the `openbank-product-catalog` pattern):
  Postgres-backed, durable across restarts. Schema: `db/migration/V1__create_credit_exposures.sql`.
- `infrastructure/rest/AnaCreditResource.kt` — `POST /api/v1/anacredit/exposures` (upsert),
  `GET /api/v1/anacredit/exposures`, `GET /api/v1/anacredit/returns/{referenceDate}` (render).

## Persistence (ADR-0037 v2)

`CreditExposureRepository` methods are `suspend` — the fleet/libs reactive convention (Mutiny `Uni`
bridged to coroutines via `awaitSuspending`; see `PostgresCreditExposureRepository`). The table is
fully relational (one scalar column per `CreditExposure` field), unlike product-catalog's
document-shaped JSONB `doc` — every field here is a first-class report/threshold attribute, not
opaque payload. `debtor_id` is indexed because the €25 000 threshold is evaluated per-debtor across
all their instruments on every `AnaCreditReturnBuilder.build()` call.

A repository/`suspend`-calling `@QuarkusTest` (e.g. `PostgresCreditExposureRepositoryIT`) must run its
body on a Vert.x duplicated context — a plain test thread has none and fails with "No current Vertx
context found". Bridge via `VertxContextSupport.subscribeAndAwait` (mirrors
`openbank-ledger-service`'s `JournalPartitionMaintainerIT`); each test needs an explicit `: Unit`
return type or JUnit5 silently skips it (Kotlin `fun x() = expr` footgun).

## Build / test

```
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-anacredit-service:test --offline
```
The domain tests are pure JUnit (fast, no boot); `AnaCreditResourceTest`, `AnaCreditBootSmokeIT`, and
`PostgresCreditExposureRepositoryIT` are `@QuarkusTest`s that boot against a Testcontainers Postgres
(`it/PostgresTestResource.kt`, mirrors product-catalog's); `AnaCreditContractTest` pins
`openapi.yaml info.version` to `version.txt`.

## v1 non-goals (documented in ADR-0037; persistence itself shipped in v2)

- **No ČNB submission transport** (SDMX/statistical-reporting channel) — renders only.
- **No counterparty reference dataset** golden-sourcing; **no quarterly accounting dataset**.
- Exposures are still **fed in via REST only** — no Kafka consumer of `balance.overdraft.*` (or any
  other) events exists yet. Persistence (this doc's subject) is now durable/Postgres-backed, but
  event *ingestion* remains an explicit, separate non-goal until a consumer is built.
- **EUR-equivalent commitment is supplied** (`committedAmountEur`) — FX conversion for the threshold
  is the caller's responsibility (`openbank-fx-service`).
