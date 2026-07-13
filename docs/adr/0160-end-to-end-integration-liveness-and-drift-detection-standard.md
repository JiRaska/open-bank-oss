# ADR-0160 — End-to-end integration liveness and drift-detection standard

Date: 2026-07-13
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): jiri.raska (paired with Claude Opus 4.8)

## Context

Two incidents investigated back-to-back on 2026-07-13 turned out to share one root cause:

- **#889** — `openbank-standing-order-service` had complete CRUD, a daily scheduler, a passing
  test suite, and full docs. It published `standing-order.due.v1` to Kafka every day for weeks.
  **Nothing consumed the event.** No payment was ever initiated. ADR-0114 (`Delivery-Status:
  Shipped`) describes the scheduler and the outbox publish, and was correct about both — but the
  ADR's own scheduler doc-comment additionally claimed *"Downstream payment rails … consume
  standing-order.due.v1 events"*, which was never true. Nothing checked that claim against the
  actual fleet.
- **#860** — a reconciliation control (ADR-0039 Phase A) reported a ~220,000 CZK ledger⇄sub-ledger
  drift, read at first as a money-path integrity failure. Investigation found the ledger was
  correct throughout; the reported number was a snapshot taken *while a backfill was rewriting the
  sub-ledger*, and the genuine steady-state residual was 200 CZK from two reversed 2026-06-07 test
  entries. The control fired correctly — but nothing distinguished "this is drift" from "this is a
  drift-shaped transient", so a real signal and a false alarm looked identical.

Both are instances of a pattern already visible elsewhere in this repo's own history, i.e. this is
not a one-off:

- `SERVICE` principal type dead code — an OPA rule gated on a principal classification that
  `AuthorizeInterceptor` never emits, unreachable since it was written (issue #266).
- Balance reconciliation itself was silently dead for 41 days after a migration was hand-edited
  post-apply (issue #855) — the control designed to catch drift had drifted out of existence.
- `openbank-fraud-onnx-baseline-adapter` (ADR-0139 Phase 1b) is tracked as real in places while
  remaining a `BaselineFraudModel` placeholder.
- `check-outbox-dispatch-enabled.sh` and `check-no-service-principal-type.sh` already exist
  **because** two earlier instances of this exact pattern (a config flag silently defaulting to
  off; a principal type silently never firing) cost real debugging time before anyone wrote a
  fleet-wide check for them. Those two scripts are proof the pattern is worth catching in CI — but
  they only cover the two specific footguns they were written for. There is no general mechanism.

The common shape: **a claim about runtime behaviour (an event is consumed, two writers agree, a
feature works end-to-end) is encoded only in prose — an ADR's delivery status, a scheduler's doc
comment, a `governance.yaml` lineage edge — and never verified against the actual fleet.** Unit
tests pass because they mock exactly the seam that is broken. A caught-and-logged exception is
indistinguishable, from the outside, from success. Nothing pages on "zero events consumed for N
days" the way something pages on "500 errors for N minutes".

This is a ~34-service fleet developed substantially by AI agents working PR-by-PR. That
development model is efficient exactly because each PR is scoped to what one agent can hold in
context — but it also means no PR author has end-to-end visibility across a producer in one
service and its (missing) consumer in another. The fleet needs the cross-service, over-time view
that no single PR review provides, encoded as CI, not as an expectation that a reviewer will
remember to check.

## Decision

We adopt four mechanisms that convert "does this actually work end-to-end, and does it keep
working" from a documentation claim into a CI-checked, alertable fact. All four follow this repo's
existing gate-graduation discipline (ADR-0144): a new check ships **advisory** (`::warning::`,
never blocks a PR) with a `target_enforce_date` and `blocked_on` entry in `rules.yaml`, and only
flips to `enforce` once a fleet sweep brings violations to zero — exactly the path
`check-outbox-dispatch-enabled.sh` already proved out.

**1. Event-consumer liveness gate.**
A new CI script (`check-event-consumer-liveness.sh`) builds a topic → {producers} / {consumers}
map from every service's `mp.messaging.outgoing`/`incoming` declarations (including multi-topic
`topics:` subscribers like audit-service/analytics-sink) and flags any topic with a producer and
zero consumers fleet-wide. A topic may be allowlisted with a one-line reason (external sink,
intentionally consumer-less audit trail, deliberately unbuilt downstream tracked by an issue
number) — same shape as an ktlint/detekt baseline file: an explicit, reviewable, individually
justified exception list, not a blanket skip.

**2. Lineage-vs-code audit.**
`governance.yaml` lineage edges (`relationType: api|creates|consumes`) must be backed by
grep-verifiable code: an `api` edge needs a matching `@RegisterRestClient`/rest-client config key
pointed at the declared target; a `consumes` edge needs a matching `@Incoming` on the declared
topic. This directly targets the #889 root cause — standing-order-service's lineage entry claiming
a `transaction-service` edge was copy-pasted boilerplate with no backing code, and nothing noticed.

**3. Shared workflow-liveness watchdog primitive.**
`ReconciliationFreshnessWatchdog` already solves "did this scheduled job actually run and
succeed recently" for one service. Extract it into `openbank-libs-runtime` as
`WorkflowLivenessWatchdog(name, expectedInterval)`: any `@Scheduled` job wraps its success path
with `watchdog.recordSuccess(name)`; a Prometheus gauge age-of-last-success pages when it exceeds
`2 × expectedInterval`. This is the direct fix for the 41-day-dead-reconciliation failure mode:
"job stopped running" becomes an alert, not a silent gap discovered by accident.

**4. Drift-SLA, not drift-log.**
`BalanceReconciliationService` (and any future control-account tie-out) reports `hasDrift` as a
single-snapshot boolean today, which is exactly what made #860's transient backfill artifact
indistinguishable from a real defect. Change the reconciliation record to carry a rolling window
(last N runs) and define drift as **actionable** only when it persists across
`consecutive_drift_threshold` consecutive runs with no intervening backfill/maintenance marker —
one bad snapshot logs at INFO, sustained drift pages. This does not weaken the control (day-zero
drift is still visible in the record for audit) — it moves the *alerting* threshold from "any
snapshot" to "sustained", which is what actually distinguishes signal from a system caught
mid-write.

None of these four replace human review or existing gates — they are additive CI/observability
layers targeting specifically the "looks done, isn't verified" failure class.

## Alternatives considered

- **Rely on more thorough PR review / a stricter Definition-of-Done checklist.** Rejected as the
  sole fix — a checklist is exactly the "encoded in prose" failure mode this ADR exists to move
  away from; #889 shipped with tests, docs, and a scheduler, and still had a checklist-shaped gap
  no reviewer caught. A checklist is complementary (see Consequences), not a substitute for a
  fleet-wide automated check.
- **Flip every advisory gate straight to enforced fleet-wide immediately.** Rejected per ADR-0144
  precedent — 19 of 35 fleet Kafka topics are currently producer-only in a first pass (many
  legitimately: planned-but-unbuilt downstreams, intentional audit-only publishes). Hard-enforcing
  immediately would break every open PR for a reason unrelated to its content and force a rushed,
  unreliable triage of 19 services in one sitting. Advisory-first with a dated graduation is the
  proven path.
- **A single mega-service "integration health dashboard" instead of CI gates.** Rejected as a
  first step — a dashboard is discoverable only by someone who goes looking for it (exactly how
  #889 stayed hidden for weeks). A CI gate is seen by construction, on every PR that touches the
  relevant surface; a dashboard is a good *second* step once the underlying signal (mechanisms 1–4)
  exists, not a replacement for generating that signal.
- **Ledger→balance projection cutover (ADR-0039 Phase D-2) as the fix for #860-class drift.**
  Considered and rejected as in-scope here — Phase D-2 (single writer, no independent-writer drift
  possible) is the *architectural* elimination of this specific drift, already decided in
  ADR-0039, and remains the correct end-state for balance specifically. This ADR's mechanism 4 is
  the general-purpose alerting layer for the (common, not just balance-specific) case of two
  independent writers reconciling — needed regardless of Phase D-2's timeline, and needed for any
  *other* pair of independent writers this fleet grows.

## Consequences

**Positive**
- Converts four instances of "an agent has to remember to check this" into "CI checks this",
  matching the fleet's existing successful pattern (`check-outbox-dispatch-enabled.sh`,
  `check-no-service-principal-type.sh`).
- Mechanism 1 would have caught #889 at PR-review time, before merge, as a `::warning::` on the
  scheduler's own claim.
- Mechanism 4 would have prevented #860's headline number from ever reading as a crisis — the
  same underlying data, correctly contextualized.
- Mechanism 3 closes the exact failure mode that let balance reconciliation run dead for 41 days
  (issue #855) recur in any other scheduled job, present or future.
- All four are stdlib-only / additive; none require new infrastructure beyond what
  `openbank-libs-runtime` and the existing Prometheus/Pyrra stack already provide.

**Negative**
- Mechanism 1's first fleet scan surfaces 19 pre-existing producer-only topics that need individual
  triage (real gap vs. legitimate allowlist entry) before the gate can graduate to `enforce` —
  non-trivial one-time cost, tracked as a fleet-sweep issue, not absorbed into this ADR.
- Mechanism 3 touches every `@Scheduled` job that opts in — a fleet-wide adoption sweep, not a
  single-service change; done incrementally, money-path services first.
- Mechanism 4 changes the reconciliation record schema (rolling window) — a Flyway migration on
  `balance-service`, money-path, needs the usual two-approval + no-behavior-regression discipline.
- A CI check can itself go stale/lie (the exact meta-risk this ADR is about) — mitigated by
  keeping the checks stdlib-only, small, and reviewed like any other code, and by mechanism 2
  cross-checking mechanism 1's own data source (`governance.yaml`) against code.

**Neutral**
- This ADR does not mandate Temporal, a service mesh, or any new runtime dependency — deliberately
  the smallest mechanism that closes the observed gap, consistent with this repo's Quartz-over-
  Temporal choice in ADR-0114 for comparable scope.

## Compliance impact

- PCI DSS: not applicable directly; strengthens change-control evidence (12.x) by making
  "this integration actually works" a verifiable CI artifact rather than a claim.
- DORA: supports Art. 9 (ICT risk detection) and Art. 24 (testing of ICT systems) — mechanism 3/4
  are detection controls for exactly the "silent failure of a critical function" scenario DORA
  testing requirements target.
- GDPR: not applicable.
- PSD2: not applicable directly; mechanism 1 reduces the risk of a silently-dead payment
  execution path (as #889 was) for regulated payment services specifically.
- CNB: supports vyhláška ČNB 501/2002 Sb. control-account tie-out expectations (mechanism 4 keeps
  the Phase A reconciliation control, ADR-0039, actionable rather than noisy).

## References

- #889 — standing-order-service never executed a payment (root cause of this ADR)
- #860 — ~220k CZK reconciliation drift, diagnosed as transient (root cause of this ADR)
- ADR-0114 — standing order execution model (the ADR whose delivery-status claim this ADR responds to)
- ADR-0039 — ledger as golden source / balance as projection (Phase D-2 is the architectural fix
  for the balance-specific case of the drift class this ADR's mechanism 4 generally alerts on)
- ADR-0144 — gate graduation: advisory rules carry an enforcement deadline (the rollout discipline
  this ADR's four mechanisms follow)
- `.github/scripts/check-outbox-dispatch-enabled.sh`, `check-no-service-principal-type.sh` —
  existing single-footgun instances of the pattern this ADR generalizes
- issue #855 — balance reconciliation silently dead for 41 days (root cause for mechanism 3)
- issue #266 — `SERVICE` principal type dead code (root cause for mechanism 2's motivating case)
