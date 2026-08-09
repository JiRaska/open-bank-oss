# openbank-statement-service

Account statements for the multi-currency current account (**ADR-0035**). Hexagonal (ADR-0002):
the domain (`domain/`) is framework-free and owns the canonical model, fail-closed reconciliation and
the three renderers; infrastructure owns the cross-service reads and the period-close store.

## The shape (read ADR-0035 before changing behaviour)

- **Unit = the per-pocket statement** (IBAN + currency). Each pocket has its own camt.053.001.08 /
  MT940 with an independent **legal sequence**. The consolidated view is a human PDF envelope with an
  *informational* (non-accounting) reference-currency total — pockets are never netted (ADR-0024).
- **Persist the model, not the files.** The only stored artefact is the `StatementPeriod` record
  (sequence + balance anchors + the frozen `StatementSnapshot` of the render inputs). camt.053 /
  MT940 / PDF are **deterministic, byte-identical projections rendered on demand** and discarded —
  never warehoused. This is legal under PSD2 Art. 58(2) ("provided *or made available* … at least
  monthly, reproducible unchanged"): we *make available*, we don't push files. Retention (10y, ČNB)
  is on the reproducible record.
- **Determinism is load-bearing, and a stored `closedAt` is only HALF of it.** Renderers take every
  timestamp from `StatementModel.closedAt`, never the wall clock — that part was always right. What
  was wrong for the whole life of the service (#3986) is that `statementModel()` rebuilt the model at
  RENDER time from live projections: `bookedEntries` for the closed window and `accountInfo` for the
  IBAN/holder name. A frozen clock cannot make a render deterministic when its *data* is re-fetched,
  and neither can the renderer tests, which are handed a `StatementModel` and never see where it came
  from. A closed period now renders from `StatementPeriod.snapshot`; **do not add a read of any live
  port to the closed-period render path**, and if you add a field to `StatementModel`, it has to come
  from the period or its snapshot, not from a port. Periods closed before Flyway V7 have no snapshot
  and fall back to the live path — that branch is deliberate, logged, and must not be "tidied away"
  into a backfill (it would freeze drift and stamp it as the issued document).
- **Fail closed.** A period-close whose computed closing (`opening ± booked movement`) disagrees with
  balance-service's closing **fails** (`ReconciliationException` → HTTP 409). Never emit a
  self-inconsistent statement.
- **Idempotent close** on `(accountId, pocketCurrency, period)`. Rendering is on-demand; the
  scheduled job only does the cheap period-close.

## Layout

- `domain/model` — `StatementModel` (canonical aggregate), `StatementPeriod` (retained record).
- `domain/reconcile` — `ReconciliationPolicy` (pure, fail-closed).
- `domain/render` — `Camt053Renderer`, `Mt940Renderer`, `PdfRenderer`, `StatementRenderer` (dispatch).
- `application/usecase/StatementService` — close / render / export / list wiring.
- `infrastructure/client` — REST-client adapters for transaction / balance / account reads.
- `infrastructure/{rest,scheduler,outbox,persistence}` — driving/driven adapters.

## Build / test

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew :openbank-statement-service:test --offline
```

Reactive (`io.smallrye.mutiny.Uni`), not suspend. Port 8136 (mgmt 8085). DB `openbank_statement`.

## Out of scope

The **PAD Art. 5 annual statement of fees** is a *push* obligation owned by the fee/billing domain —
not produced here. Account enumeration for the scheduled close (and a styled/eIDAS-sealed PDF) are
documented follow-ups.
