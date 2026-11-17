---
date: 2026-11-13
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fraud, ml, capacity, privacy-gdpr]
summary: "The fraud verdict comes from a sender-slot Valkey read and an in-memory signed snapshot, no database read, under a measured budget. Payees are keyed pseudonyms; similarity and anomaly enter only as gated features."
---

# ADR-0378 — Fraud decision hot path: a measured throughput budget, sender-slot online state, keyed payee pseudonyms and gated learning signals

Relates: ADR-0084 (fraud bounded context — replaces its §1 latency figure), ADR-0139 (ML
decisioning platform), ADR-0140 (feature store — amends its key design for fraud), ADR-0141
(model registry), ADR-0354 (financial crime as code), ADR-0374 (admission chain — degraded
behaviour per rail), ADR-0377 (fraud coverage and the typed risk context), ADR-0304
(relationship investigation), ADR-0184 (SCT Inst deadline), ADR-0027 (in-cluster substrate).

## Context

ADR-0377 makes fraud a step on every instructed value movement. At the volume this platform
is designed for, that step is on the critical path of thousands of operations a second, and
on the instant rail it shares a 10-second scheme deadline (ADR-0184) with everything else.

The adjacent decisions already settle a lot, and this ADR does not reopen them: the online
store is the in-cluster Valkey (ADR-0139, ADR-0027); online reads have a single-digit
millisecond budget and stale values read as missing (ADR-0140); models run as in-process
ONNX, signed, with shadow → challenger → champion and rules-only as the outage floor
(ADR-0139, ADR-0141); training rows are rebuilt by point-in-time replay strictly before the
decision time (ADR-0140); evaluation uses a verified local snapshot with no remote lookup
(ADR-0354 §3); and an unavailable step is a HOLD, except on the instant rail, where a step
that would exceed the budget rejects in time (ADR-0374 points 2 and 10).

What none of them decides, read against `origin/main` on 2026-11-13:

- **The budget.** The only service-level number is ADR-0084's p99 ≤ 150 ms. Nothing measures
  it, and no load scenario for `/fraud/score` exists.
- **Where the verdict's state lives.** The verdict is computed from four PostgreSQL reads
  (two velocity windows, payee history, the active threshold set) and one write per request;
  the Valkey store is read only by the shadow model. ADR-0354 keeps those reads "until the
  activation/cache consistency proof passes", which is the proof this ADR defines.
- **Who counts velocity.** The authoritative counter is built from `transaction.initiated`
  with redelivery deduplication. A scoring-time counter would be a second writer, and this
  repo has already paid for double counting on that path once.
- **How a payee is identified.** The new-payee rule keys on an internal account UUID that no
  caller has for a payee at another bank. ADR-0377 D3 replaces it with the payee's account
  identifier, which is personal data and is enumerable, so an unkeyed hash of it is not a
  pseudonym anyone should rely on.
- **Similarity and anomaly.** ADR-0304 rejects building relationships from embeddings and
  says similarity cannot create a link or an action. Nothing decides whether similarity or
  anomaly may inform a fraud score at all, or when.
- **Censoring.** A declined or held-then-rejected payment never yields a ground-truth label,
  so a model retrained on the outcomes of its own policy learns that policy.

## Decision

**D1 — The budget is set from measurement, per hop, and then enforced.** Step 0 is a
repeatable load scenario for `/fraud/score` (`perf/scenarios.yaml: fraud-score-throughput`)
that reports throughput at zero dropped iterations and p50 to p99.9 latency. The target this
ADR designs for is 5,000 decisions a second sustained per region with p99 ≤ 15 ms inside
fraud-service and a caller timeout of 100 ms; those numbers become binding only when a
measured baseline and a per-hop breakdown (TLS, serialisation, Valkey, evaluation, GC) are
recorded in a report beside the scenario. ADR-0084's 150 ms is replaced by whatever that
report supports. A number with no measurement behind it is a design target, not an SLO.

**D2 — The verdict reads no database.** The hot path is a stateless pod that holds a
verified snapshot in memory (pack, thresholds, model, reason vocabulary) and makes one
parallel round of Valkey reads per decision. PostgreSQL receives the decision record
asynchronously through the outbox. The four per-request reads are removed only after the
consistency proof: the same replayed input produces the same verdict from Valkey state and
from PostgreSQL state across a recorded day of traffic.

**D3 — Sender state lives in one slot; the stream is authoritative.** Every key the sender
owns (velocity buckets, sender features, recent-change ages from ADR-0377 D4) carries a
hash tag on the sender account, so it lands in one cluster slot and can be updated
atomically by one script. Payee, device and merchant features are read-mostly, read in
parallel and allowed to be slightly stale. Velocity is the stream-derived count from
executed movements, deduplicated on event id, plus a provisional overlay: the decision
reserves against the overlay keyed on the payment id (idempotent), the execution confirms
it, and a reject, an expired hold or an abandoned challenge releases it. Features are read
before the reservation, and that order is part of the contract, so the scored transaction
never counts towards its own features. Rolling windows are sub-bucket histograms, not
clock buckets.

**D4 — Degraded verdicts follow the rail.** On the instant rail an unavailable fraud step
rejects within the budget. On every other rail it HOLDs without opening a case and is
re-evaluated automatically on a bounded schedule, as ADR-0374 point 13 requires, so an
outage of the store does not become thousands of cases. Each degraded mode has a metric and
an alert. Overload is shed at ingress by priority; it does not become a held payment.

**D5 — A payee is a keyed pseudonym, computed in one place.** Callers send the normalised
account identifier over the existing mutually authenticated hop. fraud-service alone keys it
with HMAC-SHA-256 under a managed secret, versioned with a key id, and never persists, logs
or publishes the identifier itself. Rotation is a dual-key read window. If the key is
absent, the payee signal is unavailable; there is no fallback to an unkeyed hash.

**D6 — Learning signals are gated features, never actions.** In this order, each step only
after the previous one has shown value on labelled data:
1. rules plus graph counts computed by the stream (payers per payee, accounts per device,
   first-seen age of a payee or device);
2. a gradient-boosted model in shadow, trained on point-in-time replay (ADR-0140), with
   evaluation stratified by segment and reported as recall at a fixed false-positive budget;
3. similarity of a payee, device or account to confirmed fraud, computed asynchronously
   and written back as one feature, and per-segment anomaly scores.
Similarity and anomaly may raise a score to REVIEW; neither may DECLINE, and neither creates
a link, a hold or a case on its own (ADR-0304). Reason codes come from rules and the model's
own attributions, never from an embedding.

**D7 — A holdout keeps labels honest.** A small, randomised, pre-registered share of
low-risk decisions is not acted on beyond the existing controls and is followed to outcome,
so training can be weighted against the policy that produced the data. Its size, segments
and monetary cap are pack data under four-eyes approval, and it never applies to a payment
another control has already held.

**D8 — Rolling back a snapshot is one approval away.** The snapshot is distributed through
the declarative pointer ADR-0141 already defines, and every pod reports the hash it has
active. The previous signed snapshot stays pre-approved: activating it needs one approver and
an after-the-fact review, and its time to take effect is measured and alerted on.

## Alternatives considered

- **Keep PostgreSQL as the verdict's state and scale it with read replicas.** Smallest
  change. Rejected because replica lag makes the velocity a payment sees older than the
  payments it should count, and four round trips per decision cannot meet D1's target.
- **Count velocity at scoring time and make that counter authoritative.** One writer, one
  round trip. Rejected: a score is not an execution, and retries, holds and rejects would
  over-count with nothing to reconcile against.
- **Hash the payee at the caller.** No secret to distribute. Rejected because an unkeyed
  hash of an enumerable identifier can be reversed by enumeration.
- **Graph embeddings and nearest-neighbour search on the hot path from the start.** The
  most capable design on paper. Rejected for now: there are no labels to train or evaluate
  them, and a kNN query per decision does not fit the budget. D6 keeps the path open.
- **HOLD on every degraded or overloaded decision.** Simplest to state. Rejected: it
  contradicts ADR-0374 on the instant rail and floods the case service on every other one.

## Consequences

**Positive**
- The verdict path has one dependency, Valkey, and a defined behaviour without it.
- Velocity stays correct under retries, holds and rejects.
- No account identifier of a payee is stored in fraud-service.

**Negative**
- A provisional overlay is one more state to reconcile, per bucket.
- A managed secret is now required for the payee signal, and its absence silently weakens
  one rule; the metric and alert in D5 are the only thing that makes that visible.
- The holdout accepts a bounded, measured amount of fraud loss in exchange for unbiased
  labels. That trade is the owner's to approve per segment.

**Neutral**
- ADR-0084's latency figure is replaced by D1; the rest of ADR-0084 stands.
- ADR-0140's key layout stands for other consumers; D3 adds the sender hash tag for fraud.

### Delivery check

```bash
grep -n 'id: fraud-score-throughput' perf/scenarios.yaml
ls perf/reports/ | grep fraud-score-throughput        # a measured baseline exists
git grep -nE 'findAggregate|findHistory|thresholdRepo.findActive' -- 'openbank-fraud-service/src/main/*FraudScoringService.kt'   # prints nothing
```

## Compliance impact

- PCI DSS: not applicable — no card number or authentication data enters the fraud path.
- DORA: D1 and D4 define the latency budget and the degraded behaviour of a critical
  control; the per-rail behaviour on outage is the resilience claim to test.
- GDPR: D5 pseudonymises the payee identifier under a managed key; D6's relationship
  signals are profiling for fraud prevention and need a DPIA and a retention rule before
  step 3 is built.
- PSD2: transaction-risk monitoring is the engagement; nothing here changes when strong
  authentication is required.
- CNB: not applicable — no reporting obligation changes.

## References

- ADR-0084, ADR-0139, ADR-0140, ADR-0141, ADR-0354, ADR-0374, ADR-0377, ADR-0304, ADR-0184
- #16010 (ADR-0377 delivery tail)
