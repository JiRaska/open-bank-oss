# ADR-0159 — High-availability CNPG for money-path databases

Date: 2026-07-11
Decision-Status: Proposed   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): jiri.raska

## Context

Every CloudNativePG (CNPG) cluster in the platform runs `instances: 1` — a
single Postgres pod, no standby (43 clusters as of this ADR, all `instances:
1`). This was fine for a sandbox, but it carries two coupled costs that a
single-instance topology can never shed:

1. **No high availability.** A single-instance cluster has no failover target.
   When the primary's node is lost — spot reclaim, hardware fault, the
   memory-reclaim livelock hangs of [ADR-referenced] issue #809, or a routine
   node roll — the database is **down** until the pod is rescheduled onto
   another node in the same AZ (its EBS PVC is AZ-bound), attaches the volume,
   and completes Postgres crash recovery. That is minutes of hard downtime for
   the owning service, on the money path (ledger, payments, balances), on every
   such event.

2. **The primary is permanently un-drainable, which blocks node lifecycle.**
   `instances: 1` makes the CNPG operator create a PodDisruptionBudget with
   `minAvailable: 1` → `allowedDisruptions = 0` **forever** (there is always
   exactly one primary that must stay up). Karpenter — and any tool that drains
   via the Kubernetes eviction API — cannot evict that pod. This surfaced
   concretely on 2026-07-11: a fleet-wide kubelet-config change (issue #809)
   put every node into `Drifted=True`, but the drift roll **stalled at 15 of 26
   nodes** and went silent for hours. 39 of 43 primaries sat on the stuck
   nodes; Karpenter had drained every node it *could* and then had nothing left
   it was allowed to touch. Completing the roll required a manual, carefully
   sequenced `cordon` + `kubectl delete pod <primary>` dance across all money-
   path databases (pod *deletion* bypasses the PDB; only the eviction API
   honours it), accepting a brief per-database outage — exactly the outage HA
   is supposed to remove, incurred manually instead.

Cost 2 is not a one-off. **Every** future node lifecycle event — AMI bumps,
Kubernetes minor upgrades, kubelet-config changes, instance-type migrations,
Karpenter consolidation of a DB-hosting node — hits the same wall and needs the
same manual intervention. Single-instance CNPG turns routine platform
maintenance into a money-path downtime event.

Backups exist (barman WAL archiving + daily base backups to S3, ADR-0035) but
that is **disaster recovery**, not availability: restoring from S3 is an
RPO/RTO measured in minutes-to-hours, not an automatic failover. HA and DR are
complementary, not substitutes.

Now is the right time because #809 made the node-lifecycle cost concrete and
recurring, and because the money-path service set is already the platform's
designated higher-rigor tier (`rules.yaml: money_path_services`, 2 approvals +
threat model, ADR-0030).

## Decision

**We will run every money-path CNPG cluster with `instances: 2`** — one primary
and one hot standby with streaming replication and CNPG-managed automated
failover — with the two instances forced onto **different nodes** (and, where
capacity allows, different AZs) via CNPG's built-in anti-affinity.

Scope: the 17 money-path services in `rules.yaml: money_path_services`, mapped
to their clusters:

| Service | Cluster (namespace/name) |
|---|---|
| ledger | ledger/ledger-db |
| transaction | payments/transaction-db |
| account | accounts/accounts-db |
| balance | balances/balances-db |
| sepa-payment | payments/sepa-payment-db |
| sepa-instant | payments/sepa-instant-db |
| domestic-payment | payments/domestic-payment-db |
| clearing | payments/clearing-db |
| swift | payments/swift-service-db |
| fx | fx/fx-db |
| lending | lending/lending-db |
| sca | sca/sca-db |
| consent | consent/consent-db |
| fraud | fraud/fraud-db |
| billing | billing/billing-db |
| settlement | payments/settlement-service-db |
| sanctions | sanctions/sanctions-db |

Concretely, each of these `Cluster` manifests changes from `instances: 1` to:

```yaml
spec:
  instances: 2
  # Force the primary and standby onto different nodes; prefer different AZs so
  # a single-node OR single-AZ loss cannot take the whole cluster down.
  affinity:
    enablePodAntiAffinity: true
    topologyKey: kubernetes.io/hostname            # hard: never co-locate the two pods
    additionalPodAntiAffinityTerm:                 # soft: prefer spreading across AZs
      # (topology.kubernetes.io/zone, preferred)
```

**Replication durability:** default to CNPG **asynchronous** streaming
replication with automated failover. Async keeps the write path unaffected by
standby health (a standby outage never blocks primary writes) and, at
in-cluster streaming lag of low-single-digit milliseconds, gives an effective
RPO≈0 for the failure modes this ADR targets (node loss / drain). Synchronous
replication (zero-RPO guarantee, at the cost of write-latency and
write-unavailability when the standby is down) is **deferred to a production
hardening decision**, not adopted in the sandbox, and called out explicitly so
the trade-off is a conscious future choice rather than an accident of the
default.

Non-money-path clusters (26 of them) stay `instances: 1` for now; converting
them is a possible phase 2, weighed against cost. Until then, the manual
node-roll procedure for single-instance clusters is documented (see
References) as the accepted operational workaround for those.

`openbao-0` (the secrets store) exhibits the same un-drainable single-instance
property but is a Raft-HA StatefulSet, not CNPG, and is out of scope here —
tracked separately.

## Alternatives considered

- **Keep `instances: 1` + the manual node-roll runbook (status quo).** Zero
  added cost. Rejected: it accepts recurring operational toil on *every* node
  lifecycle event and, more importantly, accepts minutes of money-path database
  downtime on every unplanned node loss — there is no failover at all. The
  manual dance also carries its own risk (an operator deleting the wrong
  primary, or a botched sequence during an incident).
- **`instances: 2`, asynchronous replication (this decision).** HA with
  automatic failover; the PDB now permits one disruption so Karpenter drains
  and rolls cleanly with no manual step; write path unaffected by standby
  health. Cost: 2× storage + 2× compute per money-path cluster, and cross-AZ
  replication traffic when spread across zones. Accepted as the best
  cost/benefit for the stated failure modes.
- **`instances: 3`, synchronous quorum.** Full synchronous durability and
  tolerance of a single standby loss without degrading the write path.
  Rejected for now: 3× cost and write-latency overhead is overkill for the
  sandbox; it is the natural *production* target and is noted as such, not
  adopted here.
- **Karpenter `terminationGracePeriod` on the NodePool** (force-terminate a
  drifted node past its PDBs after a deadline). Solves the drain-stall but not
  the HA gap, and does so by *killing* the single primary uncoordinated —
  strictly worse than the manual dance for a single-instance DB. Rejected.
- **Automate the cordon + delete-primary dance into a maintenance-window job.**
  Removes the manual toil but still causes the downtime on every roll and still
  leaves zero HA for unplanned node loss. Rejected: it automates the symptom
  instead of removing the cause.

## Consequences

**Positive**
- Automatic failover on node loss / spot reclaim / drain — money-path database
  downtime on those events drops from minutes to a CNPG switchover (seconds).
- Node lifecycle is unblocked: the standby is evictable (PDB
  `allowedDisruptions` ≥ 0 after a switchover), so Karpenter drains and rolls
  DB-hosting nodes with no manual intervention — routine platform maintenance
  (AMI/K8s/kubelet changes) stops being a money-path event.
- HA complements the existing barman/S3 DR: a node loss no longer forces a
  restore-from-backup consideration.

**Negative**
- ~2× storage and ~2× compute for the 17 money-path clusters. At current
  sandbox sizes (2Gi storage, 100m/256Mi requests per instance) the absolute
  cost is small, but it is real and recurring; FinOps should track it.
- Cross-AZ streaming replication traffic when the standby is placed in a
  different AZ (data-transfer cost). The alternative — same-AZ standby — is
  cheaper but does not survive an AZ loss; the anti-affinity is soft on zone
  for exactly this reason.
- The rollout itself is a money-path change touching 17 databases: it must be
  staged (one cluster at a time, verify replication healthy + a test failover
  before moving on), and each PR carries money-path rigor (2 approvals + threat-
  model review, ADR-0030).

**Neutral**
- CNPG already supports this natively; no operator upgrade or new component.
- The monitoring PodMonitor already exports `cnpg_*` replication metrics
  (streaming lag, replication state) with zero further change — the signal to
  verify a healthy standby is already wired.
- Synchronous replication remains available as a future per-cluster toggle
  without revisiting this ADR's structure.

## Compliance impact

- PCI DSS: not applicable (availability topology; no change to cardholder-data
  handling, encryption, or access control).
- DORA: supports operational-resilience expectations (ICT continuity /
  tolerance for node-level failure) for money-path systems — a positive, not a
  new obligation.
- GDPR: not applicable (no change to what personal data is stored or how).
- PSD2: not applicable (no change to SCA, consent, or TPP interfaces).
- CNB: not applicable.

## References

- Issue #850 — the actionable rollout sweep (per-cluster `instances: 2`, one
  money-path PR at a time) tracking this decision.
- Issue #809 — node-hang / drift-roll-stall incident that made the
  single-instance node-lifecycle cost concrete and recurring.
- ADR-0030 — money-path rigor (2 approvals + threat model) that governs the
  rollout PRs.
- ADR-0035 — CNPG barman WAL archiving + S3 backups (the DR layer this HA
  decision complements, not replaces).
- `rules.yaml: money_path_services` — the authoritative money-path service set
  that scopes this decision.
- CLAUDE.md (Kubernetes pitfalls) — the manual cordon + `delete pod <primary>`
  procedure for rolling single-instance CNPG nodes, the accepted workaround
  until this ADR ships.
