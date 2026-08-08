---
date: 2026-07-16
decision-status: accepted
delivery-status: partial
followup: "#3806 — the one-replica-HA item; the NodePool-limit alert and request-drift items are still unfiled"
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [capacity, kubernetes, finops]
summary: "Karpenter NodePool limits (cpu 48, memory 128Gi) are declared the deliberate fleet capacity ceiling, resource requests are treated as a correctness input, and an alert is added so a binding cap stops failing silently as Pending pods."
---

# ADR-0173 — Capacity management and headroom

## Context

DORA Art. 9 expects a financial entity to manage ICT capacity and performance. The platform has a lot
of capacity *machinery* — Karpenter, KEDA, VPA, FinOps tiers, k6 — and no capacity *policy*. Nothing
says what the ceiling is, who set it, what happens when it is reached, or how we would know.

The platform audit (`docs/audits/2026-07-16-platform-audit.md` §3.3) ranked this the #1 missing ADR
domain: `grep` for "capacity planning" across `docs/adr` returns zero. `docs/bcp/dora-ictrm.md` maps
Art. 9 to access control, encryption and patching — capacity is mapped nowhere.

The gap already bit once. Issue #809: a no-swap node under memory pressure livelocked instead of
OOM-killing, and stranded the ArgoCD application-controller. The fix was four layered defenses, and the
second-order lesson (recorded in `CLAUDE.md`) was sharper: **raising resource requests pinned the
NodePool at its `limits` cap, so Karpenter refused to provision and pods went Pending** — a cost cap
silently behaving as a capacity cliff. That is a capacity-management failure, and there was no ADR for
it to be recorded in.

## Decision

**1. The NodePool `limits` are the fleet capacity ceiling, and they are a deliberate cost decision.**
`default` NodePool: **`cpu: 48`, `memory: 128Gi`** (`aws/envs/sandbox-platform/main.tf`), raised from
32 under #809. arm64-only, spot+on-demand, `c/m/r` categories, `large`…`4xlarge`. The upper bound
exists because Karpenter otherwise picked a `c6g.12xlarge` for ~20 pods; the `large` lower bound covers
~350m/400Mi of DaemonSet overhead per node.

**When the cap binds, pods go Pending — it does not autoscale past it and it does not alert.** That is
the #809 failure mode. We accept the cap (it is the FinOps control) and fix the silence: **D1** adds
the alert.

**2. Capacity is Karpenter's to allocate, and honest requests are the input.** Karpenter bin-packs by
*requests*, so a service that under-declares memory is not being thrifty — it is lying to the
scheduler, and the node it lands on is the one that livelocks. **Requests are a correctness input, not
a hint.**

Kubelet eviction headroom is set explicitly in the EC2NodeClass (`default`: `memory.available<300Mi`
hard / `500Mi` soft; `runners`: `400Mi`/`600Mi`), replacing the AL2023 default `<100Mi`, which reacts
too late. Karpenter subtracts it from allocatable, so the bin-packing arithmetic stays honest.

**3. Availability beats consolidation during the freeze window.** Disruption budgets on `default`:
**`0%` for 11h from 20:00 UTC**, `50%` otherwise. Consolidation is `WhenEmptyOrUnderutilized` after
`1m`; `expireAfter: 720h`. The freeze is cost-driven (~100 GB/night of NAT image pulls) and doubles as
an overnight stability window. It lived only in a Terraform comment; this ADR is where it now lives.

**4. Scale-to-zero is a FinOps tier, not a capacity strategy.** Tiers live in `rules.yaml: finops`
(T0 always-on = `money_path_services`; T1 HTTP→0; T2 event→0; T3 cron), with
`default_for_new_services: lowest_tier_trigger_allows` and a `money_path_sacred` guardrail. Five KEDA
objects exist fleet-wide (product-catalog, card-issuance, sdd-service, interest-service on HTTP→0;
notification-service on Kafka lag). **Note `notification-service` — the ADR-0057 T2 "proven pilot" —
runs `minReplicaCount: 1`: a warm floor, not scale-to-zero.**

**5. Capacity numbers we have not measured are targets, and we label them as such.**
`docs/strategy/06-scalability-targets.md` (Sandbox 10 TPS / Tier-B 200 / Tier-A 4,000) is self-labelled
"design targets, not yet measured". The only write benchmark
(`perf/reports/2026-07-10-money-path-write-benchmark.md`) ran **on a laptop under docker-compose,
explicitly never against sandbox EKS**, and says its own numbers are "an order-of-magnitude baseline,
not a precise SLA figure". Its real value was the five latent defects it flushed out.

We keep the targets as targets. **We will not quote them as capacity evidence**, and no SLO derives
from them.

**6. VPA advises; it does not act.** `updateMode: "Off"` for every service — recommendations surface in
the admin-ui FinOps panel and a human decides. Eviction-based right-sizing on a money path is not a
trade we want.

## Decisions to deliver

- **D1 — Alert before the NodePool cap binds.** There is no node CPU/memory saturation alert, no
  NodePool-approaching-`limits` alert, and no Pending-pod alert anywhere. The forecast rules
  (`prometheus-rules-forecast.yaml`) predict disk and PVC fill only. **The exact #809 failure mode is
  unmonitored.** *(Pending — the highest-value item here.)*
- **D2 — Enforce resource requests at admission.** No Kyverno policy requires requests/limits; 66
  Deployments vs 125 files mentioning `requests:`. Given §2 makes requests a correctness input, a
  missing request is a scheduling defect and belongs in admission, not in review. *(Pending)*
- **D3 — Fix the FinOps tier drift.** `card-issuance`, `sdd-service` and `interest-service` carry
  `openbank.io/finops-tier: T1` labels and live HTTPScaledObjects, but `rules.yaml: finops.declared`
  lists only `notification-service` and `product-catalog` — and still asserts "only 2 services have a
  declared tier today". `check-finops-tiers.py` cannot catch this: it validates the declared side only,
  and is advisory. *(Pending — `finops-tier-drift`, target 2026-09-30.)*
- **D4 — The `observability` NodePool has no memory limit and no disruption budgets.** It is the only
  NodePool in GitOps rather than OpenTofu, and the only one missing both controls. *(Pending)*
- **D5 — Karpenter runs `replicas: 1`.** The single-replica controller is the thing that provisions
  capacity; its own comment says "prod should run 2 for HA". Recorded, not fixed. *(Pending)*
- **D6 — ADR-0011's Layer 5 is fiction as written.** It claims a nightly k6 lane with a "p99 > 5%
  regression gate" against the scalability budgets. There is no nightly lane, no baseline store and no
  gate; the only k6 thresholds are advisory and the CI lane never fails a deploy on them (#334). Either
  build it or correct ADR-0011. *(Pending)*

## Alternatives considered

- **Raise the NodePool cap until pods stop pending.** The obvious reflex — and how #809 was first
  misdiagnosed. Rejected as a *policy*: the cap is the FinOps control, and removing a cost ceiling
  because it is inconvenient turns a demo repo into an unbounded bill. The cap stays; D1 makes it
  audible.
- **HPA fleet-wide.** Rejected: exactly one HPA exists (rum-gateway) and it is right that it does.
  Throughput is not the constraint here — the fleet is ~40 services at single-digit TPS in a sandbox,
  so per-service horizontal autoscaling would add moving parts to solve a load that does not exist.
  Karpenter handles the node axis; KEDA handles the zero axis.
- **VPA in `Auto` mode.** Rejected: VPA evicts pods to resize them. On a money path that trades
  availability for a cost win, and #809 already showed how badly eviction interacts with node pressure.
  Recommendations plus a human is the right speed.
- **Adopt a formal capacity model (queueing theory, headroom %).** Rejected as premature: there is no
  measured baseline against real infrastructure (§5). A model fed by laptop numbers is worse than no
  model, because it looks authoritative.

## Consequences

**Positive**
- The fleet's capacity ceiling, freeze window and eviction headroom are stated somewhere other than a
  Terraform comment.
- The #809 lesson — a cost cap behaves as a capacity cliff, silently — becomes a decision with an alert
  attached (D1) rather than folklore.
- §5 stops the scalability targets being cited as evidence they are not.

**Negative**
- Six pending decisions. This ADR makes capacity legible; it does not make it managed.
- Stating "we have no measured capacity evidence" in a public repo is a real disclosure. It is also
  true, and the alternative — quoting laptop benchmarks as SLA figures — is worse.

**Neutral**
- No runtime change; the machinery already exists. This ADR is the policy that was missing around it.

## Compliance impact

- **DORA Art. 9 (ICT protection and prevention):** this is the first artifact mapping capacity and
  performance management to Art. 9 — `docs/bcp/dora-ictrm.md` maps Art. 9 to access control,
  encryption and patching only. D1 (saturation alerting) and D2 (enforced requests) are the gaps a
  reviewer would hold against it.
- **DORA Art. 11 (response and recovery):** the 20:00–07:00 disruption freeze and the #809 defenses
  (eviction headroom, memory limits on singletons, `NodeRepair`) are availability controls; ADR-0159
  (CNPG HA) and ADR-0134 (BCP, RTO/RPO) carry the rest.
- **GDPR / PCI DSS / CNB:** not applicable — no personal or cardholder data, no reporting obligation.

## References

- [ADR-0027](0027-cloud-agnostic-in-cluster-substrate.md) — arm64 Graviton spot-first Karpenter; the substrate this sizes
- [ADR-0041](0041-serverless-tier-scale-to-zero-on-existing-cluster.md) / [ADR-0057](0057-scale-to-zero-workload-tiers-and-finops-classifier.md) / [ADR-0083](0083-t1-http-scale-to-zero-native-pilot.md) — the scale-to-zero tiers and the T1 promotion gate
- [ADR-0054](0054-finops-managed-version-lifecycle-and-cost-audit.md) / [ADR-0062](0062-finops-cost-allocation-showback.md) / [ADR-0112](0112-ai-finops-agent.md) — the FinOps controls the cap serves
- [ADR-0011](0011-testing-pyramid.md) — Layer 5 (load); D6 corrects it
- [ADR-0134](0134-business-continuity-and-dora-ictrm.md) — BCP / ICT-RM, RTO/RPO tiering
- [ADR-0159](0159-cnpg-ha-money-path.md) — CNPG HA + PDBs, the #809 follow-on
- `docs/strategy/06-scalability-targets.md` — the design targets §5 refuses to treat as evidence
- `perf/reports/2026-07-10-money-path-write-benchmark.md` — the laptop baseline, honest about itself
- `docs/audits/2026-07-16-platform-audit.md` §3.3 — the gap this ADR closes
