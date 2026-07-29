---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, compliance]
summary: "Loans exit four lawful ways: statutory withdrawal (unwind), early repayment (pure settlement quote, pack-capped compensation), bank termination (forbearance gate, notice, acceleration), payoff; all ledger-posted and evidenced."
---

# ADR-0215 — Loan termination and early-exit lifecycle

## Context

ADR-0028 built the *happy path* of a loan (disburse → accrue → collect) plus one
terminal escape hatch (`WriteOffLoanUseCase`). `LoanStatus` today is
`ACTIVE, CLOSED, WRITTEN_OFF` — too coarse for the exits the law gives the customer and
the bank. A manageable credit product needs four distinct, evidenced exit paths:

1. **Statutory withdrawal (odstoupení / Widerruf)** — the customer voids the contract
   inside the cooling-off window (ADR-0212 pack, e.g. 14 days under CCD2). The loan is
   *unwound*: principal returned, contract treated as never concluded, only statutory
   interest for the elapsed days where the law says so.
2. **Early repayment (předčasné splacení)** — a customer right with a *jurisdiction-
   capped* compensation to the bank (0,5–1 % under CCD2; CZ act specifics). Requires a
   binding **payoff/settlement quote** with a validity window.
3. **Bank-initiated termination (výpověď)** — on default (CRR Art. 178, 90-DPD via the
   shipped `Delinquency` primitive) or another statutory ground: notice with a
   pack-defined period, then **acceleration** (the whole outstanding balance falls
   due), then collection/write-off.
4. **Natural payoff** — the last installment closes the loan (exists implicitly, must
   become explicit and evidenced).

Each path moves money (unwind journals, compensation fees, accelerated balances) and
each is a supervisor magnet: the notice periods, grounds and compensation caps are
exactly what a consumer-credit examination checks first. None of this exists today —
and it must not become an operations runbook executed by hand against a money-path
book.

## Decision

**D1 — Termination is a sub-lifecycle on the `Loan` aggregate**, validated by the same
deterministic transition-policy pattern as origination (ADR-0211 D1), with the exit
branches as explicit states:

```
ACTIVE ──(cooling-off withdrawal)──► WITHDRAWN → UNWOUND            (terminal)
ACTIVE ──► EARLY_REPAYMENT_REQUESTED → SETTLEMENT_QUOTED → SETTLED → CLOSED
ACTIVE ──► DELINQUENT → DEFAULTED (DPD trigger, CRR 178) → FORBEARANCE_ASSESSED
        → TERMINATION_NOTICED → ACCELERATED → CLOSED (paid) | WRITTEN_OFF (ADR-0028)
ACTIVE ──(final installment)──► CLOSED
```

`DELINQUENT`/`DEFAULTED` are derived by the existing `Delinquency` primitive from the
oldest unpaid due date (90-DPD default; CRR Art. 178 permits a 180-day national
discretion for some retail/PSE classes — the threshold is read from the pinned pack,
defaulting to the ČNB 90-day election); `TERMINATION_NOTICED` carries the pack's
statutory notice period as a Temporal durable timer (ADR-0211 D2 reuse) — acceleration
cannot fire before the notice elapses. `FORBEARANCE_ASSESSED` is a **mandatory
documented gate, not a shortcut**: EBA/GL/2020/06 expects forbearance to be assessed
before enforcement on default, so the transition to `TERMINATION_NOTICED` requires a
recorded forbearance assessment (options evaluated, outcome, rationale, assessor) —
the *assessment* ships with this lifecycle; full forbearance execution (restructuring,
modified schedules) is the named follow-up ADR.

**D2 — The payoff quote is a pure function in `openbank-libs`.**
`SettlementQuote(loan, schedule, asOfDate, pack)` = outstanding principal + accrued
interest to the quote date + pack-capped early-repayment compensation − unapplied
overpayments. Pure, unit-tested, examiner-auditable — same house rule as
`Amortization`/`Ifrs9` (ADR-0028 D2). The quote is versioned, has a validity window,
and is pinned to the loan until it lapses; settlement against an expired quote is
rejected (fail-closed).

**D3 — Every exit posts through the existing cash path.** New `PostingKind`s in
`LendingJournalFactory` (`WITHDRAWAL_UNWIND`, `EARLY_REPAYMENT_COMPENSATION`,
`ACCELERATION`, `SETTLEMENT`) map to balanced double-entry legs — pure mapping, posted
via the ADR-0028 D3 REST journal with idempotency by posting reference. No exit
mutates a balance directly; the loan book never owns cash.

**D4 — Withdrawal unwinds, it does not "reverse-pay".** `WITHDRAWN → UNWOUND` voids the
contract: the disbursement journal is compensated by an explicit unwind journal (never
a silent delete of history), statutory day-interest is computed per the pack, and the
loan is excluded from credit-register reporting — which registers apply is declared by
the pinned pack (AnaCredit for euro-area operations per ADR-0037; the CZ registers per
the CZ pack), with the exclusion implemented as a report-time filter on the terminal
state.

**D5 — Every step is evidenced and gated.** Termination events join the ADR-0214
evidence table (ground, notice dates, quote version, settlement reference). Notices
(withdrawal confirmations, settlement quotes, termination notices) are rendered from
the pack's templates, delivered via `notification-service`, and their delivery is
evidenced like every disclosure (ADR-0212 D6, ADR-0211 D6). Bank-initiated termination
is four-eyes (`ROLE_CREDIT_RISK` maker, `ROLE_COMPLIANCE` checker, ADR-0116 mechanics);
customer-initiated exits require SCA. Grounds, notice periods and compensation caps
come **only** from the pinned pack (ADR-0212) — a jurisdiction whose termination rules
are not modelled cannot terminate programmatically; it escalates to manual legal
review. On any terminal `CLOSED`/`UNWOUND` state, registered collateral (ADR-0028
collateral capability) is released in the same transaction as the closure, and the
release is evidenced — collateral must never outlive the exposure it secures.

## Alternatives considered

- **Termination as an operations runbook** (ops executes SQL/scripts). Zero build cost,
  maximum audit exposure: unevidenced money movement on a money-path book. Rejected.
- **A separate collections/termination micro-service now.** ADR-0028 D1 already decided
  one consistency boundary for the book; acceleration and settlement must transact
  with the loan state. Rejected *for now* — if collections scale demands it, the
  sub-lifecycle can be extracted behind the events it already emits.
- **Compensation/notice rules hard-coded per country.** Rejected with ADR-0212 — the
  pack is the single source for jurisdictional numbers.
- **Immediate acceleration on default without notice state.** Operationally simpler;
  unlawful in every target jurisdiction. Rejected — `TERMINATION_NOTICED` with a
  durable timer is the legal minimum.

## Consequences

**Positive**
- The loan product becomes *completely* manageable end-to-end: originate (0211),
  decide (0213), comply (0212), evidence (0214), and exit lawfully in all four ways.
- Payoff quotes are deterministic and reproducible — the most litigated number in
  consumer credit becomes a pure, pinned function.
- Termination law is data (pack), so the same code serves CZ today and DE/EU tomorrow.

**Negative**
- Three new state clusters + four new posting kinds is the largest single change to the
  loan aggregate since ADR-0028; the transition policy needs property tests and DST
  coverage (ADR-0100) before any real book touches it.
- Withdrawal-after-disbursement interacts with already-accrued interest (ADR-0028
  accrual loop); the unwind must define accrual reversal precisely — specified in the
  build, tested as money-path.

**Neutral**
- Money-path: every phase under 2 approvals + threat-model update
  (`docs/threat-models/openbank-lending-service.md`, ADR-0030).
- Collections (dunning, forbearance, restructuring) is a follow-up ADR; this one stops
  at lawful exit.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    termination timers reuse the ADR-0101 durable-execution resilience; all
           exits evidenced per ADR-0214.
- GDPR:    customer-initiated exits under SCA; evidence retention per ADR-0214 D4
           (survives contract end per statute).
- PSD2:    SCA on customer-initiated withdrawal/early repayment confirmations.
- CNB:     zákon č. 257/2016 Sb. (withdrawal, early repayment, výpověď); CCD2
           compensation caps; CRR Art. 178 default via the `Delinquency` primitive;
           AnaCredit exclusion of voided contracts.

## References

- ADR-0028 — lending bounded context (D2 primitives, D3 cash path, write-off)
- ADR-0211 — transition-policy pattern + Temporal durable timers (notice periods)
- ADR-0212 — compliance packs (notice periods, grounds, compensation caps, withdrawal)
- ADR-0213 — (not a consumer; boundary: termination is never a *decision-engine* act,
  it is a statutory/contractual one — policy may flag, law decides)
- ADR-0214 — termination evidence records
- ADR-0116 — four-eyes mechanics (bank-initiated termination)
- ADR-0100 — deterministic simulation testing (exit-path invariants)
- `openbank-libs` `Delinquency` (CRR Art. 178), `Amortization` (schedule the quote reads)
- Zákon č. 257/2016 Sb.; CCD2 (EU) 2023/2225; CRR Art. 178 + EBA GL on default
