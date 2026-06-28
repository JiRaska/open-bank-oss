# 46. Daily FX revaluation mechanics and ČNB rate ingestion

Date: 2026-05-30
Status: Accepted
Delivery-Status: Shipped

## Context

[ADR-0025](0025-per-currency-ledger-balancing-and-fx-revaluation.md) made the
high-level decision (#3): *"A scheduled job reprices each open foreign-currency position to
CZK at the end-of-day ČNB rate and books the movement to the exchange-difference P&L
account. (Job lands in Phase 3.)"* It deliberately left the **mechanics** open. Phase 3 now
implements the job, which forces three concrete decisions ADR-0025 did not settle:

1. **What rate, from where.** Act on Accounting 563/1991 Sb. §24 mandates conversion at the
   ČNB rate. The bank's `openbank-fx-service` only models tradeable rates
   (`RateSource = ECB | REUTERS | BLOOMBERG | INTERNAL`, each a bid/ask pair). The ČNB
   *kurz devizového trhu* is a **single central-bank fixing** (one mid rate per currency,
   quoted as CZK per N units), published once per business day around 14:30 Europe/Prague.
   There is no ingestion of it today.

2. **How to compute the daily movement.** The ledger balances *per currency* (ADR-0025 #1):
   each `JournalLine.baseAmount` is carried in the GL account's **own** currency, not a CZK
   functional value (`FxConversionPosting` sets `baseAmount = native amount`). So the ledger
   does **not** already hold a CZK translation of foreign balances against which to diff. The
   revaluation needs an explicit CZK carrying anchor per currency.

3. **A revaluation entry must not move the position.** Repricing the EUR position changes its
   *CZK value*, not the number of EUR held. The entry therefore has to be **pure CZK** (it
   would otherwise be unbalanced in EUR under the per-currency invariant), and must leave the
   foreign FX-position accounts (`199x`) and the conversion posting untouched.

## Decision

### A. ČNB rate ingestion (`openbank-fx-service`)

1. Add `RateSource.CNB`. The ČNB central-bank fixing is a mid rate with no bank spread, so it
   is stored as an `FxRate` with `bidRate = askRate = midRate = fixing`, `rateType = INDICATIVE`,
   `source = CNB`, `baseCurrency = <ccy>`, `quoteCurrency = "CZK"`, valid for the fixing's
   business day (`validFrom = fixing date 00:00 Europe/Prague`, `validTo = next publish`).
2. A **pure** `CnbFixingParser` parses the published text feed. Format (the official
   `…/daily.txt`):
   ```
   30 May 2026 #104
   country|currency|amount|code|rate
   EMU|euro|1|EUR|25,145
   USA|dollar|1|USD|22,310
   ```
   The parser yields `(code, amount, rate)` → `ratePerUnit = rate / amount` (decimal comma,
   header lines skipped, only configured currencies kept). It is total and side-effect-free,
   unit-tested against captured fixtures, including the `amount ≠ 1` case (e.g. `JPY|100`).
3. A `CnbRateProvider` **port** abstracts fetching. The production adapter is a
   `@RegisterRestClient` to the ČNB feed, wrapped in MicroProfile Fault Tolerance
   (`@Retry`, `@Timeout`, `@CircuitBreaker`) like the existing outbox dispatchers. The feed
   URL and enabled currencies are config.
4. A `CnbRateIngestionScheduler` runs `@Scheduled` daily at **14:40 Europe/Prague**
   (`concurrentExecution = SKIP`), fetches, parses, and **upserts idempotently** by
   `(source = CNB, pair, fixing date)` — re-running the same day is a no-op. A manual
   re-ingest endpoint (`POST /api/v1/fx/cnb/ingest?date=`) is provided for backfill/ops.
5. Ingested ČNB rates are queryable (existing `getRate` filtered to `source = CNB`, exposed
   as `GET /api/v1/fx/rates/{ccy}/CZK?source=CNB`).

### B. Revaluation mechanics (`openbank-ledger-service`)

1. **Per-currency CZK counter-value accounts** (migration `V6`). For each foreign traded
   currency add a CZK-denominated `ASSET` account that holds the cumulative CZK mark of that
   currency's position:
   | code | name | type | ccy |
   |------|------|------|-----|
   | 1995 | FX Position Counter-Value EUR (CZK) | ASSET | CZK |
   | 1996 | FX Position Counter-Value USD (CZK) | ASSET | CZK |
   | 1997 | FX Position Counter-Value GBP (CZK) | ASSET | CZK |

2. **`FxRevaluationPosting`** — a pure domain helper (mirrors `FxConversionPosting`). For each
   currency `X` it takes the signed net foreign position `posX` (net of FX position `199x`),
   the ČNB rate `rX` (CZK per 1 X), and the current signed CZK carry `carryX` (net of `199x`
   counter-value). It computes:
   ```
   targetX = round(posX * rX, 2 CZK)          // mark-to-ČNB
   dX      = targetX - carryX                  // daily movement
   ```
   and emits, for each `dX ≠ 0`, a **CZK** pair: move the counter-value account by `dX`
   (DEBIT CV / CREDIT 5900 on a gain, CREDIT CV / DEBIT 5900 on a loss). The whole entry
   self-balances in CZK and never references a foreign currency, so the per-currency invariant
   holds and the `199x` position is unchanged. When a position closes (`posX → 0`), `targetX → 0`
   and the accumulated unrealized mark flushes back through 5900 automatically.

3. **`FxRevaluationService` + `FxRevaluationScheduler`.** A daily `@Scheduled` job at
   **15:00 Europe/Prague** (after ingestion, `concurrentExecution = SKIP`):
   reads the trial balance for the `199x` positions and `199x` counter-values, fetches the ČNB
   rate per currency from `openbank-fx-service` (RestClient, `source = CNB`), builds the entry
   via `FxRevaluationPosting`, and posts it through the existing `LedgerUseCase.postJournal`
   with idempotency key `fx-reval-{date}` (one entry per day; same-day re-run is a no-op).
   The durable signal is the posting's `JournalPosted`, written through the transactional outbox
   (ADR-0003); an additional `openbank.ledger.fx.revalued` domain notification (the per-currency
   CZK movements) is emitted after the post. A manual trigger endpoint
   (`POST /api/v1/ledger/fx-revaluation?date=`) supports ops/backfill.

### Scope

EUR, USD, GBP (the currencies seeded by `V5`) against the CZK functional currency. No change
to `FxConversionPosting`, `JournalEntry.validateBalance`, or the `baseAmount` semantics. Live
ČNB HTTP sits behind `CnbRateProvider`; correctness logic (parser, posting) is pure and
unit-tested; an integration test drives a captured fixing fixture end-to-end.

## Alternatives considered

- **Revalue against account 1990 (FX Position CZK) instead of new CV accounts.** Rejected:
  1990 aggregates the CZK legs of *conversions* at historical rates; reusing it as the mark
  anchor conflates transaction flow with valuation and loses per-currency traceability.
- **Store the CZK functional value in `baseAmount` and diff that** (ADR-0025's aspirational
  reporting role). Rejected for this job: the per-currency invariant uses `baseAmount.currency`,
  so `baseAmount` must stay native; repurposing it would break balancing and Task #7's posting.
- **Let ledger fetch ČNB directly (its own HTTP adapter).** Rejected: FX rates are
  `openbank-fx-service`'s bounded context; duplicating the source and parser there violates
  ownership and the single-source-of-truth principle. Ledger consumes via the service.
- **Use ECB rates already seeded.** Rejected: §24 mandates the **ČNB** rate for statutory
  translation; ECB is not legally substitutable for Czech books.

## Consequences

**Positive**
- Satisfies Decree 501/2002 / ČÚS 108–110 daily revaluation at the statutory ČNB rate;
  unrealized FX P&L is booked explicitly to 5900 and is per-currency auditable.
- Revaluation is a pure CZK overlay: it cannot corrupt the foreign position or the
  per-currency invariant, and it is idempotent per day.

**Negative**
- Adds an external dependency on the ČNB feed (mitigated: behind a resilient port, manual
  backfill endpoint, last-known rate retained).
- Three new GL accounts and a second daily batch (after ingestion).

**Neutral**
- `RateSource` gains a central-bank member; existing tradeable-rate flows are unaffected.

## Compliance impact

- CNB / accounting: Act 563/1991 Sb. §4(12)/§24 (ČNB-rate translation), Decree 501/2002 Sb.,
  ČÚS for banks 108–110 (foreign-currency position, **daily** revaluation, exchange
  differences to P&L). This ADR is the mechanism for ADR-0025 decision #3.
- DORA: the revaluation and ingestion jobs are scheduled reconciliation/integrity surfaces;
  both run behind fault-tolerant adapters.

## References

- [ADR-0025](0025-per-currency-ledger-balancing-and-fx-revaluation.md) — decision #3 this refines
- [ADR-0024](0024-multi-currency-account-single-iban-pockets.md) — the account model
- [ADR-0003](0003-transactional-outbox-for-kafka.md) — outbox the revaluation event reuses
- Act on Accounting 563/1991 Sb. §4(12), §24; Decree CNB 501/2002 Sb.; ČÚS for banks 108–110
- ČNB central-bank exchange-rate fixing (kurz devizového trhu), daily text feed
- `docs/strategy/multicurrency-implementation-plan.md` — phased rollout
