---
date: 2026-11-11
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, accounting-close, audit, authz]
summary: "An investigated nostro break that will never match is closed by four-eyes: one person proposes with a reason, a different senior approves. No posting; sticky against re-observation; append-only audit history."
---

# ADR-0369 — Nostro breaks that will never match are closed by a four-eyes manual resolution

## Context

ADR-0315 D7 records every item a nostro reconciliation leaves unmatched as a break with an age,
and alerts once an open break passes the age and amount threshold (`TreasuryNostroBreaksAged`).
A break could only resolve when a later reconciliation MATCHED it. A statement-side break is in
scope only for its own statement, which is immutable, so a break that people had investigated
and explained (a correspondent error, a timing difference settled elsewhere, a test artefact)
could never close and the alert fired forever. The 2026-11-04 threat-model entry recorded the
missing write-off path as a residual, and said it would need an ADR and four-eyes (#14770).

## Decision

We will let people close an OPEN break that will never match by a four-eyes manual resolution:

- An approver (`ROLE_TREASURY_APPROVER`) PROPOSES with a mandatory reason (10 to 500 characters
  after trimming). The break stays open, so it keeps ageing and counting in the aged gauge.
- A senior approver (`ROLE_TREASURY_SENIOR_APPROVER`) who is a different person APPROVES, which
  resolves the break as `MANUAL`, or REJECTS with a reason, which clears the proposal. The proposer
  is refused (`FOUR_EYES_VIOLATION`, 422) even when they hold both roles. AI agents and
  service-accounts are refused at every step. Distinct people are enforced in the domain and by a
  database CHECK (`nostro_breaks_four_eyes`), as `deals_four_eyes` does for deals.
- Nothing is posted. The item stays unmatched in the reconciliation; only the break stops ageing.
- A manual resolution is STICKY: a later reconciliation that lists the same unmatched item again
  does not reopen it. The hourly sweep re-reconciles every statement from the last 31 days, and a
  statement line never changes, so reopening on re-observation would undo every manual closure
  within the hour. A break resolved by a MATCH still reopens when the match goes away.
- Every write to a break is a compare-and-set on a `version` column: the sweep's match/reopen and
  each four-eyes step update only the row state they were computed from, so a sweep that read a
  break before an approval committed cannot overwrite it (its write matches no row and the next
  pass recomputes). An approval names the proposal it read by its `proposedAt`; a proposal
  rejected and replaced in between is a 409.
- Every step (proposed, approved, rejected) with its actor, time, reason and Idempotency-Key is
  appended to `nostro_break_resolution_history`, which refuses UPDATE, DELETE and TRUNCATE.

Separate OPA actions guard each step: `treasury.nostro.break.propose-resolution`,
`.approve-resolution` and `.reject-resolution`, for human, non-service-account principals only.

## Alternatives considered

- **A single senior approver closes the break.** This was the first implementation. It was
  rejected in review because one person could silence the control that exists to surface an
  unexplained difference. #14770 had already said a write-off path needs four-eyes.
- **Reopen a manual resolution whenever the item is observed again.** Rejected: the same
  unmatched item is observed on every hourly pass for 31 days, so every closure would be undone
  within the hour. Re-observing an unchanged item carries no new information.
- **Post a write-off journal to the ledger.** Not considered for this decision. A break is a
  difference between two records, not a balance the bank has decided to change. Posting stays a
  separate, explicit ledger act.

## Consequences

**Positive**
- An investigated break can be closed, so `TreasuryNostroBreaksAged` clears for the right reason.
- Every closure has two named people, a reason and an append-only record.

**Negative**
- Residual: a manually closed break that later genuinely matches stays `MANUAL`. The match is
  not recorded on the break; the reconciliation itself still shows it.
- The history protects the record from the service's own code paths, not from a database
  administrator.
- Residual: "a different person" compares principal names verbatim, exactly as the deal
  four-eyes rule does (`deals_four_eyes`, `Deal.approve`). Two accounts held by one person, or
  one person under two spellings of a name, are not detected. Kept consistent with deals on
  purpose; a stronger identity scheme would be a fleet decision, not this one's.

**Neutral**
- No event is published for a manual resolution; the history table is the record.

## Compliance impact

- PCI DSS: not applicable — no card data is involved.
- DORA:    not applicable — no change to ICT risk management or incident reporting.
- GDPR:    not applicable — no personal data is added; actors are staff principal ids.
- PSD2:    not applicable — the bank's own nostro accounts, not payment services to customers.
- CNB:     not applicable — no reporting output changes; reconciliation records keep both people and the reason.

## References

- ADR-0315 (treasury service domain, D7 nostro breaks)
- #14770 (write-off path for breaks that will never match)
- `docs/threat-models/openbank-treasury-service.md`
