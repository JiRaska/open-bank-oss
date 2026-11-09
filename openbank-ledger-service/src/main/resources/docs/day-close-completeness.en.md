<!-- SPDX-License-Identifier: Apache-2.0 -->
# Daily close completeness evidence

A balanced trial balance can omit an entire balanced journal. The day-close participant check
therefore requires an explicit result from interest, lending, billing and treasury. Missing and
REFUSED participants block CUTOFF -> TIED_OUT in enforce mode. In shadow mode the day may still
advance; the operator panel states that mode explicitly. A human-only waiver records its reason
and authenticated operator identity. Waivers are exceptions, not successful processing.

The ledger retains a snapshot of the evidence at the actual TIED_OUT transition, atomically with
the day and its outbox event. Later RAN reports cannot repair an incomplete historical snapshot.
The new table is additive and has no historical backfill. Image rollback preserves its history.

GET /api/v1/ledger/close-readiness reads the preceding completed calendar month by default.
An optional YYYY-MM month is bounded to one completed calendar month; older periods are stale
for promotion. Every day must have a snapshot, the same declarations, enabled checks and a
nonempty OK tie-out after cutoff and the latest successful participant report. Waivers and refusals block readiness. Each declared required job must
have an exercised successful persisted receipt during the month. Empty sweeps do not establish
that evidence, and daily accrual cannot substitute for capitalization. Readiness never changes
configuration and does not itself authorize enforcement or deployment.

Rollout order: ledger schema/read endpoint first, then the participant images carrying exercised
job evidence. Keep shadow and all existing holds until the full monthly proof and rollout review
are complete. Legacy reports remain accepted and fail monthly qualification safely. Do not infer
historical evidence, reset a counter to qualify, or replay financial jobs just to fill a report.

Pension-company books and participant-owned fund assets use separately bound book identities.
ADR-0365 proposes noncash management fee accruals: fund expense/payable and company
receivable/income. NAV publication retains durable delivery intents and independent receipts for
both books. Pending delivery refuses completion, including after a day has closed. Neither NAV
publication nor successful delivery replaces the full-chart source reconciliation: a different
verified accountant must approve the independently supplied balances before own-book close.
The engineering mapping still requires professional accounting approval and a live complete month;
other recognition categories require reviewed source evidence and are not inferred from NAV.
Repeated fee requests use navId as Idempotency-Key. Reconciliation proposals retain a scoped
request key and reject changed retries; approval retries use the proposal id and do not create
another control. Existing books require a reviewed opening/cutover before enabling delivery.
