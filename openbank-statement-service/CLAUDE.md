# openbank-statement-service

Account statements for the multi-currency current account (**ADR-0035**). Hexagonal (ADR-0002):
the domain (`domain/`) is framework-free and owns the canonical model, fail-closed reconciliation and
the three renderers; infrastructure owns the cross-service reads and the period-close store.

## The shape (read ADR-0035 before changing behaviour)

- **Unit = the per-pocket statement** (IBAN + currency). Each pocket has its own camt.053.001.08 /
  MT940 with an independent **legal sequence**. The consolidated view is a human PDF envelope with an
  *informational* (non-accounting) reference-currency total — pockets are never netted (ADR-0024).
- **Persist the model, not the files.** The only stored artefact is the small `StatementPeriod`
  record (sequence + balance anchors). camt.053 / MT940 / PDF are **deterministic, byte-identical
  projections rendered on demand** and discarded — never warehoused. This is legal under PSD2
  Art. 58(2) ("provided *or made available* … at least monthly, reproducible unchanged"): we *make
  available*, we don't push files. Retention (10y, ČNB) is on the reproducible record.
- **Determinism is load-bearing.** Renderers take every timestamp from `StatementModel.closedAt`,
  never the wall clock. If you add a field, keep re-render byte-identical (the renderer tests guard it).
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
