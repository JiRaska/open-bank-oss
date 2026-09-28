---
date: 2026-09-28
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [database, resilience, kubernetes, capacity]
summary: "CNPG clusters update by switchover, every money-path cluster is HA against a derived set (gate), Karpenter bounds node termination with a grace period and rolls drift one node at a time, and stuck states page."
followup: "The Karpenter NodePool change applies only on a manual platform-tofu dispatch; a live minor bump under switchover is not yet observed."
---

# ADR-0325 — CNPG update and node-drain resilience

## Context

**Incident, 2026-09-28 (sandbox).** One PR bumped the PostgreSQL image of ~70 CNPG clusters
from 18.1 to 18.6, and ArgoCD applied it in one sync. Two things then compounded:

1. **Every primary restarted in place.** No `Cluster` set `spec.primaryUpdateMethod`, so every
   one ran CNPG's default, `restart`. The standby is updated first, then the primary is
   restarted where it stands and is unavailable for the whole restart, even on HA clusters
   whose updated standby was ready to take over.
2. **A node drain wedged on volume detach.** Karpenter was replacing a drifted spot node (AMI
   drift) at the same time. Its pods were evicted, but 22 EBS volumes stayed mounted in the
   instance's OS. The CSI detach timed out, re-attachment on the new nodes failed with
   `VolumeInUse`, and Karpenter's termination controller stayed in
   `VolumesDetached=Unknown (AwaitingVolumeDetachment)` with no deadline. The upstream
   controller waits for detachment only until the node's termination grace period elapses,
   then terminates the instance (`TerminationGracePeriodElapsed`). The `default` NodePool
   sets no `terminationGracePeriod`, so it waited forever.

About 22 clusters were down or degraded until an operator released the VolumeAttachment
finalizers and terminated the instance by hand. Among them was `card-issuance-db`, a
money-path database running `instances: 1`. ADR-0159 decided that every money-path cluster
runs two instances, and it listed the 17 money-path services that existed then. Eight
services have joined `rules.yaml: money_path_services` since, and the clusters of seven of
them (card-issuance, standing-order, delegation, interest, psd2, sdd, treasury) shipped
single-instance. The decision was right, but nothing enforced it. Before the bump began, a
replica on the same node had already been stuck for 97 minutes in the same way. So a stuck
detach is neither rare nor caused by the bump.

## Decision

We will make both failure modes recover on their own instead of freezing:

- **D1: switchover, fleet-wide.** Every CNPG `Cluster` sets
  `primaryUpdateMethod: switchover`, with `primaryUpdateStrategy: unsupervised` so updates
  keep flowing without a human gate. An update promotes the already-updated standby, then
  restarts the old primary as a standby. A single-instance cluster has no standby, so the
  operator restarts it regardless. The setting costs nothing there and is already correct
  on the day the cluster gains a standby.
- **D2: every money-path cluster is HA, derived and enforced.** Each has `instances >= 2`,
  required hostname anti-affinity (`enablePodAntiAffinity`, `podAntiAffinityType: required`),
  a soft zone spread, and its PDB left enabled. The seven single-instance money-path clusters
  move to two instances. The gate `cnpg-update-resilience`
  (`.github/scripts/check-cnpg-update-resilience.py`, enforced) checks D1 and D2. It
  **derives** the money-path cluster set: for each money-path service, it finds the workload
  that runs that image and the `<cluster>-rw.<ns>.svc` endpoints that workload connects to. A
  money-path service it cannot resolve is reported as a finding, not skipped, so a new
  money-path service cannot slip past it the way eight slipped past ADR-0159.
- **D3: node termination has a deadline.** The `default` NodePool sets
  `terminationGracePeriod: 1h`. After an hour, Karpenter stops waiting on PDBs and on volume
  detachment and terminates the instance, which is what releases the EBS volume. The worst
  case of the 2026-09-28 wedge drops from "until someone notices" to one hour, and nobody has
  to step in.
- **D4: drift rolls one node at a time.** A `reasons: [Drifted]` budget of `nodes: "1"` is
  added next to the existing budgets, and Karpenter applies the most restrictive one.
  Consolidation keeps its 50%. An AMI release, a kubelet change or an image bump that lands
  during a drift roll then meets one disrupted node, not half the pool.
- **D5: stuck states page.** `PostgresClusterDegraded` (generated with the scrape-coverage
  rule, from the same derived cluster set) fires when fewer instances have answered a scrape
  than the manifest declares, for 15 minutes. A pod that cannot attach its volume never
  becomes a scrape target, so `PostgresInstanceDown` (`up == 0`) cannot see it.
  `NodeClaimAwaitingVolumeDetachment` fires when Karpenter has reported a NodeClaim
  `VolumesDetached=Unknown / AwaitingVolumeDetachment` for 15 minutes. `NodeStuckTerminating`
  (1h) covers only the eviction half.

**Rolling out image bumps.** With D1 and D2 in place, a fleet-wide minor bump costs each
money-path cluster one switchover (seconds) instead of a restart, so the bump no longer has to
be split into waves by hand. We do not adopt `ClusterImageCatalog` for this. A single catalog
moves every cluster together on one edit, which widens a bump's blast radius instead of
staging it. A catalog may still be worth using later to remove the duplicated image reference,
with one catalog per wave; that is a separate decision.

## Alternatives considered

- **A dedicated on-demand NodePool for database pods.** Rejected because it would not have
  prevented this incident: the node was replaced for AMI drift, and drift applies to on-demand
  nodes exactly as it does to spot. On cost: after this ADR the fleet requests ~10.6 vCPU and
  ~26 GiB across 101 CNPG instances. With hostname anti-affinity that is about three
  m7g.xlarge nodes. Taking a list on-demand price of roughly $0.16/h against the $0.057/h spot
  price measured on 2026-08-02, the delta is about +$225/month. The bill was last measured at
  ~$853/month, so that is +26%, with no reduction in the failure modes above.
- **`karpenter.sh/do-not-disrupt` on database pods, or a zero drift budget.** Rejected: a
  disruption becomes a node that can never roll. That is the #809 stall ADR-0159 was written
  to end, and the 9 to 15 days of stuck nodes in #9691. It freezes instead of recovering.
- **`primaryUpdateStrategy: supervised`.** Rejected for the same reason: every update waits
  for a human to promote, so a routine bump turns into a pile of half-updated clusters.
- **No grace period, as in ADR-0159.** ADR-0159 rejected a grace period because it would kill
  a single-instance primary without coordination. That trade-off has changed. The money-path
  clusters are HA (D2), so an expired grace period now costs them at most a failover. For the
  remaining single-instance, non-money-path clusters it costs one restart at the end of an
  hour. Without it, the cost is an unbounded outage when a detach wedges (this incident), or
  an unbounded stuck node when a PDB never permits eviction (#9691).
- **A shorter grace period (e.g. 20m).** CNPG instance pods carry a 30-minute
  `terminationGracePeriodSeconds` (the operator's `stopDelay`), and Karpenter deletes pods
  early enough to honour it. A grace period much under an hour would therefore cut Postgres'
  clean shutdown short on every drain. One hour keeps clean shutdowns intact.
- **ArgoCD sync-waves to stage image bumps.** Rejected as the primary control: they stagger
  when each cluster receives the change, not what the cluster does with it. D1 fixes the
  latter.

## Consequences

**Positive**
- A minor image bump no longer takes a money-path primary down; the primary role moves to the
  standby instead.
- A wedged EBS detach clears itself within an hour and pages after 15 minutes.
- The ADR-0159 invariant is enforced against a derived set, so it cannot silently decay again.

**Negative**
- Seven more standbys add 0.7 vCPU and 1.75 GiB of requests, plus 14 GiB of gp3. At the
  2026-08-02 spot price per vCPU that is about $5 to $8 a month of compute and about $1 a
  month of storage, under 1% of the ~$853/month bill. Cross-AZ replication traffic for these
  low-write databases has not been measured.
- After an hour, a drain force-deletes whatever still blocks it, including a single-instance
  non-money-path primary. That database restarts; this is what never wedging costs.
- A drift roll of N nodes now takes at least N node replacements in sequence.

**Neutral**
- Setting `primaryUpdateMethod` does not change the pod template, so D1 restarts nothing when
  it lands. Adding a standby to the seven clusters clones it from the primary without
  restarting the primary.
- The NodePool change is Terraform in `openbank-infra/aws/envs/sandbox-platform` and applies
  only when `platform-tofu` is dispatched.

## Compliance impact

- PCI DSS: not applicable (availability topology only; no change to cardholder-data handling).
- DORA: supports the operational-resilience posture of the money-path systems (automatic
  recovery from node-level failure without manual intervention), and records a real incident.
- GDPR: not applicable (no change to personal-data processing).
- PSD2: not applicable (no change to payment-service behaviour or interfaces).
- CNB: not applicable (no change to reporting or supervisory interfaces).

## References

- ADR-0159: High-availability CNPG for money-path databases (extended by D2; its rejection of
  a NodePool termination grace period is revisited above)
- ADR-0173: Capacity management and headroom
- `.github/scripts/check-cnpg-update-resilience.py`, gate `cnpg-update-resilience`
- `.github/scripts/check-cnpg-scrape-coverage.py`, which generates `PostgresClusterDegraded`
- `openbank-infra/aws/envs/sandbox-platform/main.tf`: `nodepool_default`
