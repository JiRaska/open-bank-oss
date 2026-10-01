---
date: 2026-09-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, kafka, database, resilience]
summary: "The 38 hand-rolled outbox repositories collapse onto one libs-runtime AbstractPanacheOutboxRepository: index-backed per-aggregate claim, next_attempt_at backoff, batched markSent, SENT purge, two gates."
followup: "none — every phase has a delivery check below; status moves to partial when Phase 1 merges"
---

# ADR-0327 — Kernel-owned outbox v2: shared repository, indexed claim, backoff, ordering, retention

## Context

ADR-0003 chose the transactional outbox; ADR-0013 moved the *invariant* parts into
`openbank-libs` (DTOs, `OutboxRepository` port, entity base, dispatch loop) and deliberately left
each service its own table, publisher and repository; ADR-0049 D3 consolidated the 29 dispatchers
onto `AbstractOutboxDispatcher`; ADR-0050 set the regulatory bar (N1 event-loop dispatch, N2
aggregate key, N3 carried idempotency id, N4 single-writer, N5 bounded retries + DEAD). ADR-0252
added the `synthetic` column, ADR-0237 the liveness gauge. None of them decided who owns the
**repository** — the claim SQL, `markSent`, `markFailed`, the count, the index, retention — and
that is where the fleet has since diverged and where three independent audits on 2026-09-30 agreed
the kernel's largest remaining gap sits. This ADR is needed because ADR-0013's "services keep their
own repository" has produced 38 copies of one 130-line class that share every defect.

The findings, each verified on `origin/main` at `9a1eee83bf` (the scripted inventory is Appendix A;
the reproduction SQL is Appendix B):

1. **38 copies, one shape.** 37 `*OutboxRepositoryImpl.kt` files (98–233 lines, median 137) plus
   `openbank-incentive-service`'s `IncentivePersistence.kt`. All 37 entities extend
   `PanacheOutboxEntity`; all 38 carry the same `UPDATE … WHERE id IN (SELECT id … FOR UPDATE SKIP
   LOCKED) RETURNING *` claim (33 in the ledger form, 4 in an aliased `candidate` form with a
   tie-break on `id`), the same `markSent`/`markFailed` bodies, the same `ORDER BY created_at ASC`,
   and all 38 override `countProcessable` — so the `OutboxPorts.kt:47` default that materialises
   every payload is live nowhere today, but remains the default a 39th adopter inherits. The
   kernel owns the entity base and the loop (`OutboxDispatch.kt`, `AbstractOutboxDispatcher.kt`)
   and no repository. `claimed_at`, which every claim writes, is not on `PanacheOutboxEntity`: 31
   service entities declare it themselves, 7 reference it only from native SQL.
2. **Ordering is not what ADR-0050 N4 promised.** N4 rested on "entries are dispatched
   sequentially" at `replicas: 1` and named `SKIP LOCKED` as a refinement. The refinement landed
   fleet-wide; the premise did not survive it. `OutboxDispatch.dispatchOnce` (`OutboxDispatch.kt`)
   continues to row N+1 of the same aggregate after row N fails, and two replicas holding disjoint
   `SKIP LOCKED` batches interleave one aggregate's events on the wire. Kafka keys by aggregate
   (N2) so a consumer sees them in *send* order, which is no longer creation order.
3. **No backoff.** No table has `next_attempt_at`; a FAILED row is re-claimed every 5 s tick, so
   10 real failures park a row DEAD in ~50 s (`OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS = 10`).
   The breaker-open case is already exempt (#4005); a *broker-slow* or *schema-registry-down*
   case is not, and it is the common one. The loop's `catch (ex: Exception)` also swallows
   `CancellationException`, so a cancelled scope keeps publishing.
4. **Throughput ceiling ~5 events/s per replica.** Batch 25 per 5 s tick, one tick drains one
   batch, each row a serially awaited Kafka send followed by `markSent` in its own transaction
   (25 transactions per batch). Only clearing overrides (`batch-size: 250`, `poll-interval: 2s`).
5. **The claim collapses under backlog** (measured on ledger DDL `V2 + V18 + V24`, 2 M SENT +
   300 k pending rows): the `(status, created_at)` index cannot serve an `OR` across two status
   predicates, so the planner takes a Seq Scan + external sort — 163 k buffers, 4–8 s per claim,
   longer than the tick. A partial index `(created_at) WHERE status IN ('PENDING','FAILED',
   'DISPATCHING')` **alone did not help**; with the predicate rewritten to
   `status IN (…) AND (status <> 'DISPATCHING' OR claimed_at < :stale)` the same claim is an
   Index Scan at 83 buffers, 1–5 ms.
6. **SENT rows are never purged.** One janitor exists (`NotificationOutboxDeadLetterJanitorJob`,
   DEAD > 30 d); `PartitionMaintenance` (libs-domain) is applied to ledger journals, not to any
   outbox. Three readers depend on outbox rows *after* dispatch: `LendingRepositories.kt:488`
   and delegation's expression index read `status <> 'SENT'` (in-flight only, purge-safe);
   `CaseThreadProjection.kt:127` maps `SENT` to `PUBLISHED_TO_BROKER` evidence (a reader of
   SENT rows, so its retention window is a contract).
7. **Observability.** `DomainMetrics` exposes `outbox.dispatched`, `outbox.dead`,
   `outbox.backlog`, `outbox.dead_lettered`; the only alert is `OutboxBacklogStuck`
   (`backlog > 100 for 15m`, tier-1). There is no backlog **age**, no claim latency, no DEAD
   *rate*. A backlog of 99 rows that are three days old is invisible.
8. **Conformance is optional.** `OutboxDispatchConformanceIT` (libs-testing) has one adopter
   (`LedgerOutboxDispatchIT`); five services carry a local `*OutboxDispatchIT`, 32 have none.

Money-path services (`rules.yaml: money_path_services`: ledger, transaction, account, balance,
sepa-payment, domestic-payment, clearing, swift, fx, lending, sdd, billing, …) all sit on this
code, so every semantic change below needs a real-DB IT and an explicit compatibility story.

## Decision

We move the outbox repository into the kernel and make its guarantees explicit. Twelve
decisions, D1–D12.

**D1 — `AbstractPanacheOutboxRepository<E : PanacheOutboxEntity>` in `openbank-libs-runtime`.**
It implements the whole `OutboxRepository` port — `claimProcessable`, `markSent` (batched, D6),
`markFailed` (with backoff, D4), `countProcessable` (`SELECT count(*)` on the partial index),
`oldestProcessableAge()` (D10) and `purgeSent(olderThan, batch)` (D8) — parameterised by the
table name and entity class only. A service repository becomes
`class LedgerOutboxRepositoryImpl : AbstractPanacheOutboxRepository<LedgerOutboxEntity>("ledger_outbox")`
plus its own `persistInTransaction`. `claimed_at` and `next_attempt_at` move onto
`PanacheOutboxEntity`; a service that already declares `claimed_at` deletes its copy in the
same PR. The `OutboxPorts.kt:47` materialising default is removed — `countProcessable` becomes
abstract on the port, so a repository that does not extend the base must write an O(1) count.

**D2 — One additive Flyway migration per service, plain `CREATE INDEX`, never `CONCURRENTLY`
from Flyway.** Each service adds exactly one `V<n>__outbox_v2.sql`:

```sql
ALTER TABLE <t>_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;
ALTER TABLE <t>_outbox ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;   -- 7 services lack it
CREATE INDEX IF NOT EXISTS ix_<t>_outbox_claim
    ON <t>_outbox (created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
CREATE INDEX IF NOT EXISTS ix_<t>_outbox_inflight_aggregate
    ON <t>_outbox (aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');   -- serves D3's per-aggregate anti-joins
```

Flyway here runs every migration inside one transaction (Quarkus default; no service sets
`executeInTransaction=false`, no migration in the tree uses `CONCURRENTLY`), and the account
V10 migration records the measured reason: a `CONCURRENTLY` build at boot races the app's own
pool connections and is cancelled by `lock_timeout`. So the migration is the plain form, which
is safe because the partial index covers only non-SENT rows — the build reads the table once
and writes a small index. **Runbook rule, not a gate:** on a table already past 1 M rows in
production, an operator pre-creates the same index by name with `CREATE INDEX CONCURRENTLY`
out of band before the deploy; `IF NOT EXISTS` then makes the migration a no-op. The old
`(status, created_at)` index is dropped in a *later* migration once the plan gate (D12) has run
green in the cluster, never in the same file.

**D3 — Per-aggregate ordering: claim by aggregate head, and stop an aggregate on its first
failure.** The claim selects the oldest eligible row **per aggregate** and locks the aggregate,
not the row:

```sql
UPDATE <t>_outbox SET status = 'DISPATCHING', claimed_at = :now, updated_at = :now
WHERE id IN (
  SELECT o.id FROM <t>_outbox o
  WHERE o.status IN ('PENDING','FAILED','DISPATCHING')
    AND (o.status <> 'DISPATCHING' OR o.claimed_at < :stale)
    AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= :now)
    -- o is the aggregate's HEAD: no older unsent row of the same aggregate exists
    AND NOT EXISTS (SELECT 1 FROM <t>_outbox p
                    WHERE p.aggregate_id = o.aggregate_id
                      AND p.status IN ('PENDING','FAILED','DISPATCHING')
                      AND (p.created_at, p.id) < (o.created_at, o.id))
    -- and nothing of that aggregate is in flight on any replica
    AND NOT EXISTS (SELECT 1 FROM <t>_outbox d
                    WHERE d.aggregate_id = o.aggregate_id
                      AND d.status = 'DISPATCHING' AND d.claimed_at >= :stale)
  ORDER BY o.created_at, o.id
  LIMIT :limit FOR UPDATE SKIP LOCKED
) RETURNING *;
```

Guarantee, stated precisely: **at-least-once delivery; for one aggregate, events reach the
broker in `(created_at, id)` order, under any number of replicas; across aggregates no order
is promised.** Mechanism: `DISTINCT ON` cannot be combined with `FOR UPDATE`, so the head is selected by
anti-join: a row is claimable only if no older unsent row of its aggregate exists and none of
its aggregate is freshly `DISPATCHING`. A replica that lost the race for row N sees N still
`PENDING` (the winner's update is uncommitted) and therefore does not qualify N+1 — MVCC is
the cross-replica guard, and it needs no JVM coordination. A failed row keeps its aggregate
parked until its `next_attempt_at`, so N+1 is never sent before N. A `DEAD` row does **not**
block its aggregate: the row is already out of order by definition (ADR-0050 N5), and blocking
would freeze the aggregate until an operator requeues; `OutboxDeadRate` (D10) is the signal. Cost: an aggregate with k pending events
needs k claims, so a hot aggregate drains at one event per drain-loop iteration rather than
per tick — with D5's drain loop that is still hundreds per second for one aggregate, and
unrelated aggregates are unaffected. A sequence gap after a stale reclaim is the one remaining
window: a pod that dies after the Kafka send and before `markSent` re-sends N (at-least-once;
consumers dedupe on `ce-id`, N3) and never sends N+1 first.

**D4 — Exponential backoff with `next_attempt_at`, DEAD after 10, DEAD is the DLQ.**
`markFailed` sets `next_attempt_at = now + min(2^attempt × 1 s, 10 min)` with ±20 % jitter; the
attempt cap stays `OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS = 10`, so a poison row lives ~35 min
before DEAD instead of 50 s, and a 10-minute broker outage burns two attempts, not all ten. The
breaker-open exemption (#4005) is unchanged. **No Kafka DLQ for the producer side**: the
CLAUDE.md DLQ rules (`failure-strategy`, explicit per-service topic, `KafkaTopic` CR, ACL) govern
*consumers*; a row that could not reach the broker cannot be dead-lettered *to* the broker.
The DEAD row in the service's own table is the producer DLQ, `openbank.outbox.dead_lettered`
its gauge, and `POST …/outbox/requeue` (already an assessed four-eyes action in
`rules.yaml:1078`) its replay. The loop catches `Exception` and rethrows `CancellationException`
before `markFailed`.

**D5 — Drain loop.** One tick claims and publishes batches until a batch returns fewer rows
than `batchSize` or a wall-clock budget of `poll-interval × 0.8` is spent, whichever first, so
a burst of 1 000 rows drains in one tick instead of 40. `concurrentExecution = SKIP` keeps
ticks from overlapping in one JVM.

**D6 — Batched `markSent`, concurrent sends bounded by D3.** Within a claimed batch every row
belongs to a different aggregate (D3), so the sends are independent: the base publishes them
with bounded concurrency (`Semaphore(16)`), then one
`UPDATE … SET status='SENT', sent_at=:now, attempt_count = attempt_count + 1 WHERE event_id = ANY(:ids)`
for the successes and one `markFailed` per failure. Transactions per batch: 25 → 2. The 5 s
tick with batch 25 becomes ~ (25 × 16 / RTT) events/s per replica, bounded by Kafka, not by the
loop.

**D7 — No LISTEN/NOTIFY.** Rejected: Hibernate Reactive's pool has no dedicated listen
connection, CNPG PgBouncer in transaction mode drops `LISTEN`, and D5 already takes the worst
case latency from `poll-interval` to one tick with no idle cost. Revisit only if a p99 publish
latency target below 5 s is ever written down.

**D8 — Retention by batched purge, not partitioning.** `purgeSent(olderThan = 7 d,
batch = 5 000)` runs nightly from the base dispatcher (`DELETE … WHERE id IN (SELECT id … WHERE
status='SENT' AND sent_at < :cut LIMIT :n)` until short), and DEAD rows after 30 days
(matching the notification janitor, which is then deleted). Partitioning was rejected:
`PartitionMaintenance` exists but converting 38 live tables to partitioned ones is a
table-rewrite migration per service, and the reader in finding 6 needs rows *by status*, not
by month. The 7-day SENT window is a **contract** for `CaseThreadProjection`; a service that
needs longer sets `openbank.outbox.retention.sent-days` explicitly, and a reader of SENT rows
is a documented exception, not a discovery.

**D9 — Conformance IT mandatory.** A new enforced gate `outbox-conformance-it-present`
(`.github/scripts/check-outbox-conformance-it.py`): every module that extends
`AbstractOutboxDispatcher` must carry a test extending `OutboxDispatchConformanceIT`
(detected by the `: OutboxDispatchConformanceIT()` supertype, never by file name — same
lesson as the Pact "grep for the word contract" bullet). Baseline: the 37 modules without one,
each entry naming its migration PR; a baseline entry that becomes covered is a finding.
The kit itself gains four tests: per-aggregate order under two concurrent dispatchers,
backoff timing, stale reclaim, and purge leaving non-SENT rows alone.

**D10 — Observability.** `DomainMetrics` gains `registerOutboxOldestAge(service, …)`
(`openbank_outbox_oldest_age_seconds`, from `oldestProcessableAge()`),
`outboxClaimLatency(service)` (timer, `openbank_outbox_claim_seconds`) and the existing
`outboxDead` counter is alerted on as a rate. Rules added to `prometheus-rules-tier1.yaml`:
`OutboxOldestRowStale` (`oldest_age_seconds > 300 for 10m`, critical — the alert that finding 7
lacks), `OutboxClaimSlow` (`histogram_quantile(0.99, claim_seconds) > 1 for 15m`, warning — the
early signal of finding 5 returning), `OutboxDeadRate` (`increase(dead_total[1h]) > 0`, warning,
per service). `OutboxBacklogStuck` stays. Re-derive each at t = 0 on a cold pod: age is 0 with
no rows (seed the gauge from an empty query, never from `EPOCH`; the ADR-0237 lesson).

**D11 — A ratchet on hand-rolled claim SQL.** `outbox-claim-sql-ratchet` (advisory for one
release, then enforced): any `FOR UPDATE SKIP LOCKED` inside `openbank-*/src/main` outside
`openbank-libs-runtime` is a finding unless baselined; the baseline is today's 38, keyed by
file, and shrinks with every migration PR. detekt cannot see SQL in string templates, so this is
a script gate, not a detekt rule.

**D12 — Plan gate.** A Testcontainers IT in libs-runtime (`OutboxClaimPlanIT`) loads the
canonical DDL, inserts 200 k SENT + 20 k pending rows, runs `EXPLAIN (ANALYZE, BUFFERS)` on the
base class's own D3 claim SQL (anti-joins included) and asserts **no `Seq Scan` node and `shared hit + read < 500`**. It
runs in libs-runtime's own CI, so the claim SQL cannot regress without a red PR; the per-service
plan is the same statement against the same DDL shape, which is what D2's single migration
guarantees. The before/after throughput numbers are recorded in `openbank-libs-benchmarks` when
that module lands (parallel PR); the plan gate does not wait for it.

### Migration plan

| Phase | Scope | Gate state | Approvals |
|---|---|---|---|
| 1 | libs-runtime: base class, `PanacheOutboxEntity` columns, `OutboxDispatch` drain/backoff/cancellation, conformance-kit tests, `OutboxClaimPlanIT`, `DomainMetrics` additions, alert rules; both gates **advisory** with the 38-entry baselines | additive, no service changes | 1 |
| 2 | non-money-path services (26): one PR each — repository collapses onto the base, one migration, conformance IT, baseline entries removed | ratchets shrink | 1 |
| 3 | money-path services (12): same PR shape, real-DB IT proving the ordering guarantee and the atomic claim per service | ratchets shrink | 2 + threat-model diff |
| 4 | `outbox-conformance-it-present` and `outbox-claim-sql-ratchet` flip to **enforced**; `NotificationOutboxDeadLetterJanitorJob` and the `OutboxPorts.kt:47` default deleted; drop the old `(status, created_at)` indexes | baselines empty | 1 |

Compatibility: a service on the old repository and a service on the base coexist — the port
does not change shape, `next_attempt_at NULL` means "eligible now", and a v1 dispatcher ignores
the column. Rolling back Phase 2/3 for one service is reverting its PR; the migration stays
(additive, unused).

## Alternatives considered

- **Ordering A — stop the whole batch on first failure.** Simplest; preserves order but one
  poison aggregate halts every other aggregate on that replica, and two replicas still
  interleave one aggregate. Rejected: it fixes the intra-replica half only.
- **Ordering B — serialise sends per partition key inside the JVM.** Groups the batch by
  aggregate and sends each group sequentially. Rejected: it cannot see the other replica's
  batch, so it does nothing for the cross-replica case that `SKIP LOCKED` created.
- **Ordering C (chosen) — claim by aggregate head + park the aggregate on failure.** The only
  option whose guarantee holds for N replicas, because the invariant lives in the row lock,
  not in a JVM. Costs one claim per event per hot aggregate.
- **Retention — partition SENT rows by month via `PartitionMaintenance`.** Rejected in D8: a
  table rewrite per service and the readers want status, not time.
- **Wake-up — LISTEN/NOTIFY.** Rejected in D7.
- **Ownership — a Quarkus extension (`openbank-quarkus-outbox`).** ADR-0013 deferred it and
  the reason holds: a build-time processor for what is one abstract class. Revisit once the
  base has 38 adopters and the API has not moved for a quarter.
- **Ownership — keep per-service repositories and fix all 38 by sweep.** Rejected: that is the
  ADR-0049 D3 sweep again, and the audits show what a sweep leaves behind (4 divergent claim
  forms, 7 entities without `claimed_at`, 1 conformance adopter).
- **Backoff — leave 5 s retries, raise the DEAD threshold.** Rejected: it trades a 50 s poison
  window for a longer broker-outage hammer; `next_attempt_at` is one nullable column.

## Consequences

**Positive**
- One claim statement, one index, one backoff policy, one purge, one plan gate; the next
  outbox defect is fixed in one module.
- ADR-0050 N4's ordering promise becomes true under `replicas > 1` and Argo canary windows.
- Throughput per replica moves from ~5 events/s to broker-bound; backlog collapse (finding 5)
  becomes structurally impossible while the plan gate is green.

**Negative**
- 38 migration PRs, 12 of them money-path with two approvals and a threat-model diff each.
- A hot aggregate is throttled to one event per drain iteration.
- Purging SENT rows makes the outbox stop being an incidental audit trail; the one reader that
  used it that way (`CaseThreadProjection`) gets a documented window instead.

**Neutral**
- Gate: `outbox-conformance-it-present` and `outbox-claim-sql-ratchet`, both new under
  `.github/scripts/`, advisory in Phase 1, enforced in Phase 4.
- The `DEAD` state, `OutboxFailurePolicy`, `ce-id` headers and the dispatcher's CDI-annotation
  placement rule (ADR-0013) are unchanged.

### Delivery check

```bash
# Phase 1 landed: the base class exists and the plan gate runs in libs-runtime CI
test -f openbank-libs-runtime/src/main/kotlin/com/openbank/libs/persistence/outbox/AbstractPanacheOutboxRepository.kt
test -f openbank-libs-runtime/src/test/kotlin/com/openbank/libs/persistence/outbox/OutboxClaimPlanIT.kt
# Phases 2–3: the ratchet baseline shrinks; expected 0 hits when Phase 4 is done
grep -rl 'FOR UPDATE SKIP LOCKED' --include='*.kt' openbank-*/src/main | grep -v openbank-libs-runtime | wc -l
# every dispatcher owner carries a conformance IT; expected: two equal numbers
grep -rl ': AbstractOutboxDispatcher(' --include='*.kt' openbank-*/src/main | sed 's#/src/main.*##' | sort -u | wc -l
grep -rl ': OutboxDispatchConformanceIT()' --include='*.kt' openbank-*/src/test | sed 's#/src/test.*##' | sort -u | wc -l
# Phase 4: both gates enforced
grep -A3 -E 'id: outbox-(conformance-it-present|claim-sql-ratchet)' .github/gates/gates.yaml | grep -c 'mode: enforced'   # expected 2
```

## Compliance impact

- PCI DSS: not applicable — no cardholder data path changes; card-issuance's outbox carries
  the same payloads it does today.
- DORA: the engagement named by ADR-0013 and ADR-0050 (operational resilience of the event
  relay) is what this ADR strengthens — ordering under scale-out, bounded retry, and an alert on
  backlog age. No new article is cited here.
- GDPR: not applicable — retention of SENT rows shortens (7 days); no payload content changes.
- PSD2: not applicable.
- CNB: not applicable.

## References

- ADR-0003, ADR-0013, ADR-0049 (D3), ADR-0050 (N1–N5), ADR-0237, ADR-0252.
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/persistence/outbox/OutboxPorts.kt`,
  `OutboxDispatch.kt`, `OutboxFailurePolicy.kt`;
  `openbank-libs-runtime/.../persistence/outbox/AbstractOutboxDispatcher.kt`, `PanacheOutboxEntity.kt`;
  `openbank-libs-testing/.../testing/outbox/OutboxDispatchConformanceIT.kt`.
- `openbank-ledger-service/src/main/resources/db/migration/V2__create_ledger_outbox.sql`, `V18`, `V24`.
- `openbank-account-service/src/main/resources/db/migration/V10__account_search_trgm.sql`
  (why Flyway and `CONCURRENTLY` do not mix here).
- `openbank-infra/gitops/components/observability/prometheus-rules-tier1.yaml` (`OutboxBacklogStuck`).
- Issues: #1201 (atomic claim), #4005 (breaker-open exemption), #5128, #8353, #11525.

## Appendix A — inventory of the 38 outbox repositories (scripted, `origin/main` @ `9a1eee83bf`)

Derived by a grep script over `openbank-*/src/main` (no build): impl size, table from the
claim SQL, claim shape, `countProcessable` override, index DDL from `db/migration`, dispatcher
overrides, extras, conformance IT presence. Uniform where it matters: 38/38 `SKIP LOCKED`
claims, 38/38 `countProcessable` overrides, 37/37 entities on `PanacheOutboxEntity`, 37/38
ordered by `created_at`, 1/38 batch override. The outliers are the last column.

| Service | Impl lines | Table | Claim SQL shape | `countProcessable` override | Index DDL on the table | Batch / poll override | Extras a base must accommodate | Conformance IT |
|---|---|---|---|---|---|---|---|---|
| account-service | 136 | `account_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| aml-service | 168 | `aml_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | yes |
| balance-service | 136 | `balance_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| billing-service | 233 | `billing_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity`; 233 lines — extra billing-specific query surface | no |
| card-issuance-service | 192 | `card_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | yes |
| case-coordinator-agent | 108 | `case_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `CaseThreadProjection` maps outbox `status` (incl. `SENT`) to evidence stage — a reader of SENT rows | no |
| clearing-service | 136 | `clearing_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | batch 250 / poll 2s (`application.yaml`) | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| consent-service | 136 | `consent_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| delegation-service | 144 | `delegation_outbox` | `candidate` alias form | yes | `(aggregate_id, (((payload::jsonb ->> 'reservationVersion')::BIGINT)))` partial: WHERE event_type = 'DelegationSpendReservationStateChanged' AND status <> 'SENT'; `(status, created_at)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity`; expression index on `payload::jsonb->>'reservationVersion'` WHERE `status <> 'SENT'` (a reader of non-SENT rows) | no |
| dispute-service | 137 | `dispute_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| document-service | 136 | `document_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| domestic-payment | 141 | `domestic_payment_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| engagement-service | 145 | `engagement_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| fraud-service | 145 | `fraud_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| fx-service | 136 | `fx_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| interest-service | 172 | `interest_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | yes |
| kyb-service | 126 | `kyb_outbox` | `candidate` alias form | yes | `(status, created_at)` | default 25 / 5s | — | no |
| kyc-service | 136 | `kyc_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| ledger-service | 182 | `ledger_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | yes |
| lending-service | 151 | `lending_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity`; `LendingRepositories` joins `lending_outbox` WHERE `status <> 'SENT'` (reader of non-SENT rows) | no |
| loyalty-service | 141 | `loyalty_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | — | no |
| notification-service | 140 | `notification_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity`; `NotificationOutboxDeadLetterJanitorJob` (DEAD > 30 d nightly) | no |
| party-service | 137 | `party_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| pid-service | 136 | `pid_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| referral-service | 131 | `referral_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| sanctions-service | 136 | `sanctions_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| sca-service | 136 | `sca_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| sdd-service | 153 | `sdd_outbox` | ledger form | yes | `(event_id)`; `(status, created_at)` | default 25 / 5s | — | yes |
| security-scanner | 98 | `ict_incident_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id, aggregate_revision)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity`; `aggregate_revision` column + `(aggregate_id, aggregate_revision)` index | no |
| sepa-payment | 141 | `sepa_payment_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| standing-order-service | 136 | `standing_order_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| statement-service | 138 | `statement_outbox` | ledger form | yes | `(status, created_at)` (dropped and re-created twice across migrations) | default 25 / 5s | — | yes |
| swift-service | 141 | `swift_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| tpp-registry-service | 136 | `tpp_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| transaction-service | 144 | `transaction_outbox` | ledger form | yes | `(status, created_at ASC)`; `(aggregate_id)` | default 25 / 5s | `claimed_at` declared in the service entity, not in `PanacheOutboxEntity` | no |
| treasury-service | 131 | `treasury_outbox` | `candidate` alias form | yes | `(status, created_at)` | default 25 / 5s | — | no |
| wealth-service | 131 | `wealth_outbox` | `candidate` alias form | yes | `(status, created_at)` | default 25 / 5s | — | no |
| incentive-service | (in `IncentivePersistence.kt`) | `incentive_outbox` | ledger form + `claim_token` UUID, ORDER BY `occurred_at` | yes | `(status, occurred_at)` | default 25 / 5s | `claim_token` column, `occurred_at` ordering column; not a `*OutboxRepositoryImpl` file | no |

## Appendix B — reproducing the claim-plan measurement (finding 5)

Against a scratch Postgres with ledger's `V2 + V18 + V24` DDL applied:

```sql
INSERT INTO ledger_outbox (event_id, aggregate_id, event_type, payload, status, sent_at, created_at, updated_at)
SELECT gen_random_uuid(), gen_random_uuid(), 'x', '{}', 'SENT', now(), now() - (g || ' seconds')::interval, now()
FROM generate_series(1, 2000000) g;
INSERT INTO ledger_outbox (event_id, aggregate_id, event_type, payload, status, created_at, updated_at)
SELECT gen_random_uuid(), gen_random_uuid(), 'x', '{}', 'PENDING', now(), now() FROM generate_series(1, 300000);
ANALYZE ledger_outbox;
-- v1 claim (today's CLAIM_SQL): Seq Scan + Sort, ~163k buffers, 4–8 s
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM ledger_outbox
 WHERE (status IN ('PENDING','FAILED')) OR (status = 'DISPATCHING' AND claimed_at < now() - interval '2 min')
 ORDER BY created_at ASC LIMIT 25 FOR UPDATE SKIP LOCKED;
-- v2: partial index + rewritten predicate: Index Scan, ~83 buffers, 1–5 ms
CREATE INDEX ix_ledger_outbox_claim ON ledger_outbox (created_at, id) WHERE status IN ('PENDING','FAILED','DISPATCHING');
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM ledger_outbox
 WHERE status IN ('PENDING','FAILED','DISPATCHING') AND (status <> 'DISPATCHING' OR claimed_at < now() - interval '2 min')
 ORDER BY created_at ASC LIMIT 25 FOR UPDATE SKIP LOCKED;
```

The index alone leaves the `OR` form on a Seq Scan; the predicate rewrite is what lets the
planner prove the partial index covers the query. `OutboxClaimPlanIT` (D12) is this script as a
test.
