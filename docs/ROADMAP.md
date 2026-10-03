# OpenBank Roadmap

Source review: **2026-10-03**. This is the current navigation page for the
[M1–M7 acceptance plan](strategy/09-roadmap-M1-M7.md). The review checked repository
sources and recorded evidence; it did not rerun the fleet, inspect the live cluster,
or certify regulatory compliance.

Milestones describe outcomes. **Implementation, deployment configuration, and a passing
acceptance exercise are different evidence.** No completion percentage is inferred from
file counts or ADR status. Component releases remain independent of platform milestones.

| Milestone | Current source-backed position | What is needed to close it |
|---|---|---|
| M1 — Foundation | Build conventions, CI gates, contracts and component releases exist; ongoing maintenance | Fresh-clone contributor exercise and current build/security evidence for the claimed scope |
| M2 — Resilience | Temporal and shared outbox infrastructure exist; outbox v2 adoption is underway | Fleet adoption, real failure/retry/compensation tests, reconciliation and coverage evidence |
| M3 — Compliance evidence | Audit, privacy, identity, reporting and control-mapping implementations exist | Complete evidence mappings, external conformance and independent review |
| M4 — Observability and operations | Metrics, SLO configuration, synthetic journeys, performance and recovery workflows exist | Current journey coverage, actionable alerting and repeatable drill/load results |
| M5 — Security baseline | Security gates, image signing, evidence generation and conformance self-assessments exist | Independently reviewed control effectiveness and explicitly scoped assurance claims |
| M6 — Multi-region active-passive | Deferred by [ADR-0186](adr/0186-single-region-deployment-and-disaster-recovery-posture.md) | Regional failover/failback and measured cross-service RTO/RPO |
| M7 — Active-active and scale | Longer-term target, dependent on recovery and capacity proof | Conflict safety, sustained/burst/soak tests and independent operator validation |

The [detailed plan](strategy/09-roadmap-M1-M7.md) keeps the acceptance criteria and
source evidence together. M1–M5 have implementation evidence; this review does not declare
any whole milestone accepted. There is no promised completion date or single platform version.

## Workstreams represented in current source

These are active design/implementation areas, not a new maintainer priority ordering:

- **Shared resilience:** [outbox v2 design](adr/0327-kernel-owned-outbox-v2.md) and
  [runtime implementation](../openbank-libs-runtime/src/main/kotlin/com/openbank/libs/persistence/outbox/).
  The design's front matter still says `planned` while shared v2 code exists; consult
  per-service adoption and delivery evidence before calling the migration complete.
- **Platform lifecycle:** [CNPG update/drain resilience](adr/0325-cnpg-update-and-node-drain-resilience.md)
  and [Gateway API migration](adr/0324-replace-retired-ingress-nginx-with-gateway-api-on-envoy-gateway.md).
  Staged infrastructure is not proof that traffic has migrated.
- **Banking capability depth:** [card capability ports](adr/0283-card-platform-scheme-agnostic-capability-ports.md),
  [banking context graph](adr/0303-banking-context-graph-and-authorized-hybrid-retrieval.md),
  and the [current ADR registry](adr/CURRENT.md) for credit, products, treasury and governed agents.
- **Operational evidence:** [synthetic journeys](../.github/workflows/synthetic-journeys.yml),
  [performance gates](../.github/workflows/perf-gate.yml), and
  [quarterly restore verification](../.github/workflows/dr-restore-verify.yml).
  Their scope and successful run artifacts determine what they prove.

## Known gaps (honest list)

- **Live external integration is an acceptance task.** Payment rails, card schemes,
  identity/credit providers and regulator submissions require operator credentials,
  agreements, provider qualification and end-to-end tests. A stub, simulator or adapter
  in the source tree does not establish that connectivity.
- **Recovery proof is narrower than platform recovery.** A ledger restore is recorded in
  the [DR test log](bcp/dr-test-log.md), and restore verification is now scheduled quarterly.
  The workflow explicitly does not measure RPO or cross-service recovery consistency.
  Neither proves M6 regional failover or its RTO ≤ 30 min / RPO ≤ 5 min targets.
- **Security and compliance evidence is scoped.** Automated checks and self-assessments
  are not blanket ASVS, FAPI, SLSA or regulatory certification. See the
  [evidence pack](compliance/evidence-pack.md) and [FAPI self-assessment](compliance/fapi2-self-assessment.md).
- **Product readiness varies.** Feature flags, provider adapters, approval controls and
  the corresponding contract/integration tests must be checked per journey. The customer
  application lives in a separate repository and was not release-audited here.
- **Performance evidence is not fleet-wide capacity proof.** Existing test lanes have
  selected workloads; [scalability targets](strategy/06-scalability-targets.md) remain
  targets until their exact workload and duration are demonstrated.
- **Documentation delivery labels can lag code.** Use source and test evidence alongside
  ADR metadata; record discrepancies rather than translating `shipped` into live health.

The old July gaps are not a reliable inventory: the net-settlement ledger leg now has
an [implemented design](adr/0281-net-settlement-ledger-leg.md), the fraud
service contains a rule engine, and local Compose no longer uses PostgreSQL 16. Likewise,
public launch is history, not a pending milestone.

## Maintaining this roadmap

Update the acceptance plan in the same PR that changes a criterion or supplies its
proof. Link the implementation and a dated, scoped verification result. Keep historical
drill results as historical evidence. Use [issues](https://github.com/JiRaska/open-bank-oss/issues)
for actionable work, [ADRs](adr/) for decisions, and component changelogs for releases.

OpenBank distributes software. Operating a bank, holding a licence and joining payment
schemes remain operator responsibilities; this roadmap does not promise those outcomes.
