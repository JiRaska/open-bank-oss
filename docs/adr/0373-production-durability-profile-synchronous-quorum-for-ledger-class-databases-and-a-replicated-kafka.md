---
date: 2026-11-11
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, database, kafka, finops]
summary: "Ledger-class databases run three instances with synchronous quorum commit, and Kafka runs three brokers with replication factor 3 and min in-sync 2. The sandbox runs the same profile, because an unrun profile is a claim."
---

# ADR-0373 — Production durability profile: synchronous quorum for ledger-class databases and a replicated Kafka

## Context

ADR-0159 gave money-path databases two instances with asynchronous replication and deferred
synchronous replication "to a production hardening decision". ADR-0186 accepted a single
region. No ADR decides the Kafka broker topology; `kafka.yaml` says in a comment that
production "should" use three brokers. This ADR is the deferred decision.

Measured on `origin/main` f0216a5686:

- `gitops/components/ledger/postgres.yaml`: `instances: 2`, no synchronous setting. A
  failover can lose transactions the primary had acknowledged.
- `gitops/components/kafka/kafka.yaml`: one combined controller and broker node,
  `default.replication.factor: 1`, `min.insync.replicas: 1`.
- 67 `replicas: 1` entries across the `KafkaTopic` manifests of 35 components.
- There is one environment. A production profile that exists only as a comment has never
  been applied, failed over, upgraded or measured.

The ledger is the golden source (ADR-0039): balances, statements and regulatory returns are
rebuilt or reconciled from it. Other money-path databases can be reconciled against the
ledger after a failover; the ledger has nothing to be reconciled against.

## Decision

1. **Durability class is declared per database.** Every CNPG cluster carries a durability
   class. `ledger-class` means an acknowledged commit survives the loss of any one instance.
   Members: the ledger database and every separate-entity ledger deployment (ADR-0337).
   All other clusters keep their ADR-0159 setting.
2. **Ledger-class runs three instances with synchronous quorum commit** on at least one
   standby, spread across nodes and zones. Three, not two: with two, losing the standby
   stops writes; with three, one instance can be lost or drained with writes continuing and
   no acknowledged commit lost.
3. **Kafka runs three brokers** with replication factor 3 and minimum in-sync replicas 2 as
   cluster defaults and on every declared topic, internal topics included. Producers on the
   outbox path require acknowledgement from all in-sync replicas.
4. **The sandbox runs this profile.** The only way to know the profile works is to run it:
   failover, node drain and rolling upgrade are exercised where the manifests live.
5. **Migration is staged and reversible per step.** Kafka: add brokers, reassign existing
   partitions to three replicas, then raise the minimum in-sync value; the minimum is raised
   last so no step makes a topic unwritable. Database: add the third instance, wait for it
   to stream, then switch to synchronous commit.
6. **Evidence.** After the change a drill kills the ledger primary under write load and a
   Kafka broker under produce load, and records acknowledged-but-lost writes (expected zero)
   and time to recover in `docs/bcp/dr-test-log.md`.

Sandbox resource delta, from the manifests' own requests:

| Change | CPU request | Memory request | Storage |
|---|---|---|---|
| Kafka, 1 to 3 nodes | +500m | +2304Mi | +10Gi |
| ledger-db, 2 to 3 instances | +100m | +256Mi | +2Gi |

The money cost is not stated here because it has not been measured; the PR that applies the
change carries the measured delta against current cluster spend.

## Alternatives considered

- **Keep the sandbox at one broker and two async instances; write the profile down only.**
  Cheapest. Rejected: this is the current state, and it is why the profile is unproven.
- **Synchronous commit with two instances.** No extra instance. Rejected: the standby
  becomes a write dependency, so a routine node drain stops the ledger.
- **Synchronous quorum for every money-path database.** Uniform and simple to explain.
  Rejected: one extra instance per cluster for databases that reconcile against the ledger;
  revisit per database when a reconciliation cannot restore it.
- **Managed streaming or database service.** Moves the durability work to a provider.
  Rejected here: ADR-0174 already records provider concentration as an open exit risk.

## Consequences

**Positive**
- An acknowledged ledger commit and an acknowledged event survive the loss of one node.
- Kafka upgrades and node drains stop being an outage for every producer.

**Negative**
- Ledger write latency includes a standby round trip. Measured in the applying PR.
- More pods on a spot-first cluster (ADR-0027): simultaneous reclaim of two ledger instances
  or two brokers stops writes. Placement across zones and capacity types is part of delivery.
- Still one region. A regional loss is outside this ADR and stays with ADR-0186.
- Three brokers are not three controllers. Step 1 found that the operator documentation lists
  scaling a controller node pool as unsupported, so the brokers are added as a broker-only
  pool and cluster metadata keeps a single controller until a controller migration is
  decided separately. Read from the current documentation, not verified for the pinned
  operator version. Until then a controller loss still stops the cluster.

**Neutral**
- Planned gate `durability-class-declared`: every CNPG cluster declares a class, a
  ledger-class cluster has three instances with synchronous commit, and no declared topic
  has fewer than three replicas. Enforced in the PR that applies the profile.

### Delivery check

Not implemented in this revision:

```bash
grep -rhE '^\s+replicas:' openbank-infra/gitops/components/*/kafka-topic*.yaml | sort | uniq -c   # only 3
grep -nE 'instances|synchronous' openbank-infra/gitops/components/ledger/postgres.yaml          # 3 and a quorum setting
grep -n 'id: durability-class-declared' -A4 .github/gates/gates.yaml | grep 'mode: enforced'
grep -n 'acknowledged-but-lost' docs/bcp/dr-test-log.md                                           # a dated drill entry
```

## Compliance impact

- PCI DSS: not applicable — no cardholder data handling changes.
- DORA: strengthens availability and recovery of the accounting record and the event
  backbone; the drill in point 6 is the evidence, the manifest alone is not.
- GDPR: not applicable — no personal data processing changes.
- PSD2: not applicable — no payment or authentication flow changes.
- CNB: not applicable — no reporting or prudential calculation changes.

## References

- ADR-0027, ADR-0039, ADR-0159, ADR-0174, ADR-0186, ADR-0337
- `docs/bcp/dr-test-log.md`
