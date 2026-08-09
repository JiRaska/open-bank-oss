---
date: 2026-07-23
decision-status: accepted
delivery-status: partial
followup: "#669, #2365 — tested restore runbooks and DR drill cadence; M6 multi-region is roadmap, not a tail"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience]
summary: "We accept single-region deployment for the sandbox and defer multi-region active-passive DR to Milestone M6; recovery rests on CNPG WAL archiving, GitOps-reconstructable config and tested restore runbooks until M6 tightens RTO/RPO."
---

# ADR-0186 — Single-region deployment and disaster-recovery posture

## Context

The platform runs in a single cloud region on one Kubernetes cluster (ADR-0027 cloud-agnostic
in-cluster substrate, ADR-0010 GitOps). A regional loss would take the whole platform down; there is
no warm standby in a second region. The Business Continuity Policy (`docs/bcp/bcp-policy.md`, DORA
Art. 11) already classifies critical ICT functions into recovery tiers and states its T0 targets
(RTO 4 h, RPO 15 min) as **aspirational until Milestone M6 (multi-region active-passive)** is
certified, at which point they tighten to contractual RTO ≤ 30 min / RPO ≤ 5 min.

That single-region acceptance and the M6 roadmap live in the BCP document but were never captured as
an architecture decision with its mitigations and trade-offs. This ADR records the decision so the
posture is explicit and auditable, and so "why is there no second region?" has a documented answer
rather than an implied one.

## Decision

We **accept single-region deployment for the sandbox / reference deployment** and **defer
multi-region active-passive DR to Milestone M6**, with the current posture resting on tested recovery
capabilities rather than geographic redundancy:

1. **Backups are continuous and off-cluster.** CloudNativePG streams WAL to S3 (continuous
   archiving, 14-day retention) enabling point-in-time recovery via `recovery.targetTime`. This is
   the primary RPO control and is exercised — see `docs/bcp/automated-dr-restore.md` and the DR test
   log (`docs/bcp/dr-test-log.md`).

2. **Configuration is reconstructable from git.** All infra and app state is declarative under
   ArgoCD + GitOps (ADR-0010), so a cluster can be rebuilt from the repository; there is no
   snowflake state to lose. Secrets recover from OpenBao with break-glass keys held in AWS Secrets
   Manager.

3. **Recovery is procedural and tested, not assumed.** Named runbooks cover PITR restore, Kafka
   topic loss, cluster re-init, OpenBao break-glass and a full DR restore
   (`openbank-infra/docs/runbooks/`), and the DR test cadence is defined in the BCP policy. Recovery
   depends on human-executed runbooks within the tier RTOs, not on automated regional failover.

4. **The RTO/RPO tiers are aspirational until M6.** Today's T0 targets (RTO 4 h, RPO 15 min) are
   stated as goals; they become contractual (RTO ≤ 30 min, RPO ≤ 5 min) only once M6 multi-region
   active-passive infrastructure is in place. We do not claim tighter numbers than the single-region
   architecture can currently deliver.

5. **Multi-region is roadmap, not silent debt.** M6 (active-passive across regions) is the recorded
   next step; this ADR is its anchor. Until then, the residual risk of a full-region outage is
   knowingly accepted for a sandbox that holds no production customer money.

## Alternatives considered

- **Build multi-region active-passive now.** Rejected for the current phase: it roughly doubles
  standing infrastructure cost (a warm second-region cluster, cross-region replication, data-egress)
  for a sandbox with no real customer funds, and the operational complexity (replication lag, split-
  brain avoidance, failover drills) is not justified before the platform carries production load.
- **Claim the tightened RTO/RPO now.** Rejected: single-region PITR restore cannot honestly meet
  RTO ≤ 30 min / RPO ≤ 5 min, and asserting targets the architecture cannot deliver is the exact
  vacuous-compliance failure the platform avoids elsewhere.
- **Active-active multi-region.** Rejected as far beyond current need: it forces conflict resolution
  and quorum concerns on the money path that active-passive avoids; not warranted before M6.

## Consequences

**Positive**
- The single-region acceptance and its mitigations are documented and defensible under DORA, with a
  clear roadmap to the tighter targets.
- Recovery rests on continuously tested capabilities (WAL archiving, GitOps rebuild, runbooks), not
  on untested assumptions.

**Negative**
- A full-region loss is an accepted, un-mitigated-by-redundancy risk today; RTO depends on manual
  runbook execution and cannot meet the contractual targets until M6.
- Cross-region data residency and egress questions are deferred with M6.

**Neutral**
- The BCP policy (`docs/bcp/bcp-policy.md`) remains the operational source of truth for tiers,
  targets and cadence; this ADR records the decision behind it, not a second copy of the tables.

## Compliance impact

- PCI DSS: not applicable — no card data at rest in scope of this decision.
- DORA:    Art. 11 (ICT business-continuity) and Art. 12 (backup/restore) — addressed by the BCP
           policy, tested restore and runbooks; single-region residual risk is explicitly accepted.
- GDPR:    backups contain PII; retention (14-day WAL) and erasure interplay follow the data-lifecycle
           policy (ADR-0118).
- PSD2:    not applicable to this infrastructure decision.
- CNB:     regulator notification within 4 h of a T0 disruption is defined in the BCP crisis-
           communication plan; unchanged here.

## References

- `docs/bcp/bcp-policy.md` — Business Continuity Policy (tiers, RTO/RPO, M6 roadmap)
- `docs/bcp/automated-dr-restore.md`, `docs/bcp/dr-test-log.md`, `docs/bcp/dora-ictrm.md`
- `openbank-infra/docs/runbooks/` — PITR, Kafka recovery, cluster ops, break-glass, full DR restore
- ADR-0010 — Kubernetes + ArgoCD GitOps
- ADR-0027 — cloud-agnostic in-cluster substrate
- ADR-0009 — Postgres-per-service (CNPG)
