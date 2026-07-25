# Money-path chaos drill — procedure

Status: **procedure only, not yet executed** (issue #669, scope item 2).

`automated-dr-restore.md` proves the backup path (CNPG PITR restore from S3, quarterly). It does
not cover what this document does: killing a money-path pod **mid-flow** and forcing a CNPG
**live failover** (primary → replica promotion) **while postings are in flight**, and checking
whether ledger invariants survive. Those are different failure modes — one is "can we restore
from a backup", the other is "what happens to an in-progress transaction right now" — and the
second was never exercised.

Executing this against the sandbox EKS cluster touches shared infrastructure (pod kills, forced
Postgres failover) and needs the same "explicit go-ahead" treatment as any other shared-state
change. This document defines the procedure so the drill is reproducible once someone runs it —
it deliberately stops short of running it or inventing an RTO number. Do not backfill an
"observed" figure below without having actually run the drill; see `dr-test-log.md`'s own format
for where the real numbers get recorded once they exist.

## Pre-conditions

- Sandbox EKS cluster, money-path services deployed (`ledger`, `transaction`, `balance`,
  `payment`, `clearing` — BCP tier T0, `bcp-policy.md` §T0).
- A load generator producing a steady stream of postings through the money-path write path —
  reuse `perf/k6/money-path-write-benchmark.js` (issue #669 scope item 1) at a moderate, not
  peak, rate so individual failed/retried requests are distinguishable in logs.
- Baseline captured before either scenario: `GET /api/v1/ledger/close/trial-balance` returns
  `balanced: true`, and the outbox backlog (`openbank_outbox_backlog` metric) is at steady
  state for every money-path service.

## Scenario A — pod kill mid-flow

Kill a `transaction-service` (or `ledger-service`) pod while it holds in-flight requests, not
between requests.

1. Start the load generator.
2. `kubectl delete pod -n transaction <pod> --grace-period=0 --force` on a pod currently serving
   traffic (confirm via `kubectl top pod` / active connection count, not by picking at random).
3. From the moment of deletion, record:
   - Client-observable error rate and shape (5xx vs connection reset vs timeout) until traffic
     against the service returns to baseline — this interval is the **RTO** for this scenario.
   - Whether any in-flight posting produced a **duplicate** entry (the idempotency-key uniqueness
     constraint should have prevented this — check `ledger_schema` for one), or a **partial**
     entry (one leg of a double-entry posting committed without its pair).
   - Whether the outbox backlog spikes and drains back to baseline once the replacement pod is
     Ready, or whether any event is lost (compare producer offsets before/after).
4. Post-scenario invariant check: trial-balance still `balanced: true`.

## Scenario B — CNPG failover during posting

Force the `ledger-db` (or `transaction-db`) CNPG cluster to fail over from primary to replica
while postings are actively being written.

1. Start the load generator against the same database's owning service.
2. `kubectl cnpg promote <cluster>-<replica-pod> -n <ns>` (or delete the current primary pod) to
   force a replica promotion.
3. From the moment of promotion, record:
   - Wall-clock from failover start to the service's readiness probe going green again against
     the new primary — this interval is the **RTO** for this scenario.
   - Any query error surfaced to the application during the connection-pool failover window, and
     whether Quarkus's reactive pool retried and recovered without a restart.
   - Whether any transaction that was in-flight at the moment of promotion committed on the old
     primary but is absent from the new primary (a genuine RPO gap, not just RTO) — compare the
     load generator's client-side "accepted" log against what's actually in `ledger_schema` on
     the new primary.
4. Post-scenario invariant check: trial-balance still `balanced: true`; no duplicate or orphaned
   double-entry legs (same check as Scenario A).

## Recording the result

Once actually executed, append an entry to `dr-test-log.md` using its existing format
(`Type: live-failover`, `Scope: T0`), with the real measured RTO for each scenario and every
invariant-check outcome — pass or fail. A `FAIL` result (a duplicate posting, an unbalanced trial
balance, an RTO blown past the T0 target) is itself the useful outcome of this drill; do not
treat anything short of PASS as a reason to withhold the entry.

This closes the "chaos/DR drill, documented" scope of issue #669 only once that log entry with
real numbers exists — this procedure document is necessary but not sufficient for that.
