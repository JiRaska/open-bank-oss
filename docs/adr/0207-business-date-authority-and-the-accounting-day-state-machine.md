---
date: 2026-07-26
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [accounting-close, ledger, statements]
summary: "Make the accounting day an owned, persisted concept in ledger-service with an OPEN/CUTOFF/TIED_OUT/LOCKED state machine, and derive the business date from one authority instead of 45 UTC clock beans plus 5 hand-rolled Prague zones."
---

# ADR-0207 — Business date authority and the accounting day state machine

## Context

The platform has no owner of the business date, and no period lock finer than the
fiscal year. Both are load-bearing for money-path correctness, and both are currently
enforced by convention. Measured against `origin/main` for this ADR, not quoted from
the audit that raised it (#1302):

**There is no single clock.** 45 services produce a CDI `Clock` bean and *every one of
them* is `Clock.systemUTC()` — the producers are byte-identical. Five services
(`ledger`, `transaction`, `fx`, `dispute`, `simulation`) additionally declare their own
`ZoneId.of("Europe/Prague")` in application code. In `openbank-ledger-service` the two
regimes coexist inside one service: `ClockProducer.kt:16` produces UTC, while
`LedgerService.kt:71` and `YearCloseService.kt:56` construct `Clock.system(BANK_TIME)`
in their constructors and never consult the injected bean.

The consequence is a boundary that moves depending on which object you ask. Between
22:00 and 00:00 UTC in summer, the injected clock and the domain clock disagree about
what day it is. Every "today", every close cutoff, and every default `entryDate` derived
from one of them is therefore two hours out of step with every one derived from the
other, for two hours a day, half the year. Nothing detects this, because both answers
are individually plausible and there is no third party that knows which is right.

**The period lock is year-granular.** `LedgerService.postJournal` and `reverseJournal`
are the only callers of `requireOpenPeriod`, and it takes `entryDate.year`
(`LedgerService.kt:82`, `:181`, `:246`). Its whole body asks
`yearCloseRepository.isFiscalYearAttested(fiscalYear)`. There is no month, and no day.

`command.entryDate` is caller-supplied and has no floor. So a journal can be booked into
any day of the current fiscal year, including a day that has already been tied out,
reconciled and reported. That is not hypothetical: this repo already runs an end-of-day
tie-out and a control-account reconciliation, which surfaced a real ~220k CZK
ledger-versus-sub-ledger drift. A backdated posting silently invalidates a tie-out
computed before it — the numbers were right when they were produced and become wrong
afterwards, with no event recording that they did.

**Why now.** The two defects compose, and in the worst direction. The lock that would
prevent a backdated posting is the one that does not exist below the year; the clock
that decides which day a close is closing is the one with two answers. Every remaining
item of #1302 (items 3–5) is described by its own author as collapsing into wiring once
this is settled, so this is the blocking piece, and the piece nobody can safely guess at.

## Decision

We will make the accounting day an **owned, persisted concept** rather than a value each
caller derives, and `openbank-ledger-service` will own it — it is already the golden
source for the journal and already owns fiscal-year attestation, so day state belongs
beside year state rather than in a new service with a second lifecycle.

**1. One business-date authority.** A `BusinessDate` / `AccountingClock` abstraction in
`openbank-libs-domain` (per the ADR-0122 domain/runtime split — it is pure date logic
with no framework surface) becomes the only supported way to answer "what accounting day
is it". Every close scheduler and every default `entryDate` reads it. Constructing
`Clock.system(...)` or calling `Instant.now()` for an accounting date in a money-path
service becomes a violation enforced by a CI guard, not a review convention: the current
defect survived because both halves look correct in isolation, which is exactly the
condition a human reviewer cannot be expected to catch and a guard can.

The 45 identical `ClockProducer` beans are not the problem and are not in scope to
delete. Wall-clock UTC is the right answer for timestamps. The problem is that
*accounting date* was being derived from a wall clock at all; it is a domain value with
its own calendar and cutoff, and it needs a type that says so.

**2. A day state machine: `OPEN → CUTOFF → TIED_OUT → LOCKED`.** Persisted in
ledger-service, one row per accounting day, transitions monotonic and append-only:

- `OPEN` — postings accepted normally.
- `CUTOFF` — the day's business is done and the tie-out is running. New postings for this
  day are refused; postings for the next day are accepted.
- `TIED_OUT` — reconciliation passed. The day's figures have been published downstream.
- `LOCKED` — the day is evidence. Nothing may be written to it by any path.

**3. `requireOpenPeriod` gains a day check, and reversals obey it.** The posting path
consults day state before the year check, since the day is the tighter constraint. A
reversal of an entry in a `TIED_OUT`/`LOCKED` day is not refused outright — a reversal is
exactly what is needed in that situation — but it books to the current open day with an
explicit link to the original entry, never into the closed day. Rewriting a tied-out day
in place is the operation being removed; correcting it forward is not.

**4. Day state is published, not polled.** Ledger-service emits each transition on the
existing outbox. Consumers that need to know whether a day is closed react to that rather
than querying ledger-service on every posting. A synchronous query from every money-path
service into ledger-service on the hot path would make ledger-service a hard availability
dependency of all of them — a worse failure than the one being fixed.

**Sequencing.** (1) and (2) land before (3): the lock has nothing to consult until day
state exists. (3) ships behind a flag that starts in shadow mode — the check runs and
records what it *would* have refused, without refusing — so the volume of currently-legal
backdated postings is measured before any of them start failing. Turning on a new refusal
blind, on the money path, is how #1197 killed five workloads for four days.

## Alternatives considered

- **Keep the year-only lock and add an alert on backdated postings.** Cheap, and it would
  have surfaced the drift. Rejected as the primary answer because it is detection where
  the requirement is prevention: an attested period that can still be written to is not
  attested, and an alert after the fact does not restore the tie-out. Worth doing anyway
  as part of the shadow-mode step in (3), where it *is* the measurement.

- **Put the business date in one shared config value (`bank.timezone`) and leave each
  service deriving its own date from it.** Removes the Prague/UTC split with almost no
  code. Rejected because it fixes the smaller half: every service would agree on the
  *timezone* while "what day is it, and is that day still open" stays a derived value with
  no owner. The tie-out defect survives it completely — a correct timezone still lets you
  post into a reconciled day.

- **A separate accounting-calendar service owning day state.** Cleanest separation on
  paper. Rejected for this decision: day state and fiscal-year attestation are one
  lifecycle, and splitting them across a service boundary creates two things that can
  disagree about whether a period is closed — reintroducing, at larger scale, the exact
  defect being fixed. Revisit if a second consumer needs to *own* calendar data rather
  than read it.

- **Derive day state from the existing tie-out job's output instead of persisting it.**
  Tempting, since the tie-out already knows when it passed. Rejected: it makes the lock a
  property of a scheduler having run, so a scheduler that silently never runs leaves every
  day permanently open. Five schedulers in this repo were in exactly that state (#2148,
  #2187), three of them money-path — a measured failure mode here, not a theoretical one.

## Consequences

**Positive**
- A tied-out day cannot be rewritten, so reconciliation figures stay true after the fact —
  the property that makes them evidence rather than a snapshot.
- One answer to "what accounting day is it", with a guard that keeps it that way. The
  ledger-service dual-regime bug becomes structurally impossible rather than currently
  untriggered.
- #1302's items 3–5 reduce to wiring against a settled interface.

**Negative**
- A new persisted concept on the money path, with a migration and a new failure mode: a
  day stuck in `CUTOFF` because the tie-out did not complete blocks postings for that day.
  Needs an explicit operator transition and an alert, both of which must exist before
  enforcement turns on.
- Callers that legitimately backdate today will start being refused. Shadow mode measures
  how many before that happens, but some will be real and will need a forward-correction
  path instead.
- More coupling to ledger-service — mitigated by publishing state rather than serving it
  synchronously, but not eliminated.

**Neutral**
- The 45 `ClockProducer` beans stay as they are. This ADR does not touch wall-clock time.
- No change to fiscal-year attestation, which keeps working exactly as it does now; the
  day check sits in front of it.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is involved in accounting-period state.
- DORA:    engages operational resilience of a critical function. This introduces a new
           state that can block postings, so its stuck-state detection and recovery path
           are part of the change, not a follow-up.
- GDPR:    not applicable — accounting day state carries no personal data.
- PSD2:    not applicable — no change to payment-initiation or account-information APIs.
- CNB:     bears on the integrity of accounting records that feed regulatory reporting: a
           reported period that can still be written to cannot be relied on afterwards. No
           requirement number is cited because none was consulted in reaching this
           decision.

## Delivery

**Increment 1 (this ADR's D1, D2, D4, and D3 in shadow mode).**

Shipped:
- `com.openbank.libs.domain.calendar.AccountingClock` in `openbank-libs-domain` — the business-date
  authority (D1). `LedgerService` and `YearCloseService` no longer construct
  `Clock.system(Europe/Prague)`; the dual-regime bug inside ledger-service is gone, and the bank
  zone is declared exactly once (`AccountingClock.BANK_ZONE`).
- `ledger_accounting_day` (Flyway `V21`) + `AccountingDayRecord` — the `OPEN → CUTOFF → TIED_OUT →
  LOCKED` state machine (D2), monotonic single-step, no reopen, every transition carrying an actor.
- `AccountingDayTransitioned` on the existing outbox, written in the same transaction as the state
  change (D4). Consumers react; nothing polls ledger-service per posting.
- `AccountingDayResource` — the operator surface (`/api/v1/ledger/accounting-days`), reads gated to
  the ledger read roles, every state change operator-only. OpenAPI `info.version` 1.11.0 → 1.12.0
  (additive, ADR-0048).
- `AccountingDayLock` (D3) wired into `LedgerService.postJournal` **before** the year check, and
  into `reverseJournal` as a forward-correction route rather than a refusal.
- `.github/scripts/check-accounting-clock.py` — the CI guard D1 asks for, scope derived from
  `rules.yaml: money_path_services`.

**The day lock ships in `shadow`** (`openbank.ledger.day-lock.mode`, default `shadow`): it records
what it would have refused and refuses nothing. Nothing in this increment changes a booking date or
rejects a posting that is legal today. Enforcement is a config flip, and its precondition is
evidence, not a calendar: `openbank_ledger_day_lock_decisions_total{outcome="would_refuse"}` at zero
or every hit explained.

**Increment 2 (the two enforcement preconditions above).**

Motivated by measurement, not schedule: on 2026-08-07 the live cluster had **zero** rows in
`ledger_accounting_day` while postings flowed — every day-lock decision was `no_day_record`
(confirmed in the shadow counter) and `would_refuse` could structurally never fire, so the
enforcement evidence gate was green about nothing. A lock whose calendar nobody maintains
measures nothing.

Shipped:
- `AccountingDayScheduler` — reconciles the persisted calendar toward the `AccountingClock`
  every tick (default */15 min, cluster-locked, suspend per #2187): opens the current day,
  backfills gaps since the latest known row (oldest-first, bounded — history before the
  scheduler existed is deliberately NOT fabricated), cuts over past `OPEN` days, and advances
  `CUTOFF → TIED_OUT` only on a tie-out run that is OK **and** recorded after the cutoff — a
  verdict from while the day could still change proves nothing about its final figures.
  `TIED_OUT → LOCKED` stays operator-driven: locking is the statement/period-close act, which a
  timer cannot know.
- The stuck-`CUTOFF` alert: gauge `openbank_ledger_accounting_day_stuck_cutoff_days`
  (re-published every tick; threshold `openbank.ledger.accounting-day.stuck-cutoff-hours`,
  default 8h against a normal ~6h residence) + `AccountingDayStuckInCutoff` in
  `prometheus-rules-accounting-day.yaml`.
- Dispatch proven by `LedgerSchedulerVertxContextIT` driving the real cron against a real
  Postgres, alongside the two #2187 regressions.

Not yet built, and deliberately out of this increment:
- The enforce flip itself (`openbank.ledger.day-lock.mode: enforce`). Its evidence gate is now
  real: with the calendar maintained, `would_refuse` measures actual backdated postings. Read
  the counter after the scheduler has run in the cluster over at least one full day cycle.
- #1302 items 3–5 (reconciliation false drift, FX rate staleness, statement correction path), which
  this ADR's Context predicted would "collapse into wiring once this is settled".
- The three pre-existing accounting-clock sites the new guard surfaced outside ledger-service
  (`transaction` `SettlementDateResolver`, `fx` `CnbRateIngestionService`, `sanctions`
  `SanctionsListService`) — hence the guard is advisory, not enforcing, on arrival.

## References

- Issue #1302 — no owner of the business date, no period lock below fiscal year.
- Issue #869 — the fiscal-year attestation lock this extends downward to the day.
- `openbank-ledger-service/src/main/kotlin/com/openbank/ledger/infrastructure/ClockProducer.kt`
- `openbank-ledger-service/src/main/kotlin/com/openbank/ledger/application/usecase/LedgerService.kt`
- ADR-0122 — domain/runtime library split, where the abstraction belongs.
