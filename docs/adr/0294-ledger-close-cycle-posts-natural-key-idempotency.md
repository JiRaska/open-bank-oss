---
date: 2026-09-07
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "Ledger close-cycle POSTs are path-addressed natural keys: day open/transitions conflict on replay, year-close and period-close drafts refresh idempotently; no synthetic keys needed."
---

# ADR-0294 — Ledger close-cycle POSTs are idempotent on path-addressed natural keys

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. Four ledger-service POSTs were baselined as
uncovered; all four are close-cycle operations whose entire identity already lives in the URL:

- `POST /api/v1/ledger/accounting-days/{businessDate}` — open a posting day
- `POST /api/v1/ledger/accounting-days/{businessDate}/transitions/{to}` — advance the day
- `POST /api/v1/ledger/close/{fiscalYear}` — create the year-close draft
- `POST /api/v1/ledger/periods/{type}/{date}` — create the closed-period draft

## Decision

Verified in code; no fix was needed — each endpoint is already enforced, and the baseline
entries re-point here:

- **Open day** — check-first on the natural key (`findByDate`), a repeat is a loud **409**
  ("a day is opened once"), never a duplicate row; `uq_accounting_day_business_date` (V21) is
  the race backstop. A replay is a client-visible conflict, not silent corruption.
- **Transition** — the aggregate enforces monotonic single-step progression
  (OPEN → CUTOFF → TIED_OUT → LOCKED); repeating the current stage, skipping, or moving
  backwards is 409 by construction (ADR-0207 D2). There is deliberately no reopen.
- **Year-close draft** — idempotent refresh while DRAFT (stable record id, only the trial-balance
  snapshot moves); 409 once ATTESTED; `uq_year_close_fiscal_year` (V9) is the backstop.
- **Period draft** — same shape: refresh while DRAFT, 409 once FROZEN; `uq_closed_period`
  (V22) is the backstop.

## Alternatives considered

- **Synthetic `Idempotency-Key` header** — rejected: every one of these operations is addressed
  by its full natural key in the path (a date, a year, a period). A synthetic key would identify
  nothing the URL does not and would give ops tooling a second, divergent identity for the same
  statutory record.
- **Silent 200 replay on day open** — rejected: an accounting day is statutory bookkeeping
  infrastructure; a repeated open SHOULD be observable as a caller error (409), because a caller
  that believes it opened the day and did not is a process defect worth surfacing — unlike an
  accrual retry, where the retry is the expected transport behaviour (contrast ADR-0291).

## Consequences

- Four baseline entries re-point here; the OpenAPI descriptions already state the replay
  semantics and now name this ADR; `info.version` bumped PATCH.
- Close-cycle replay semantics are uniform: drafts refresh, terminal states conflict.

## Compliance impact

Strengthens the auditability of the statutory close cycle (accounting-day lock, year close,
period freeze) — replay behaviour is now a documented contract tied to the four-eyes and
fail-closed hash verification these endpoints already enforce. No new obligation introduced.
