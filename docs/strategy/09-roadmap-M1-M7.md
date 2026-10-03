# Roadmap M1–M7: acceptance plan

Source review: **2026-10-03**. Read the [roadmap overview](../ROADMAP.md) first.
This revision replaces the July percentages and obsolete implementation inventories.
The original acceptance goals remain below; historical snapshots are available in Git history.

## How to read status

“Present” means source or a named artifact exists. “Recorded” means a dated result is
available for its stated scope. Neither means every component passes today. Completion
requires the listed acceptance evidence and reviewer sign-off. This documentation review
has not rerun production exercises or the complete test fleet.

The milestone IDs remain stable. Independent components release through
[release-please](../../release-please-config.json); milestones do not assign a monorepo
alpha/beta/1.0 version. The repository is already public. Old engineer-week estimates
have been withdrawn because their implementation assumptions no longer describe the project.

## M1 — Foundation hardening

**Position:** build and contributor infrastructure present; current acceptance not reverified.

Evidence: [build conventions](../../build-logic/), [CI workflows](../../.github/workflows/),
[gate manifest](../../.github/gates/gates.yaml), [contributor guide](../../CONTRIBUTING.md),
[contract assets](../../openbank-contracts/) and [event specification](../asyncapi/openbank-events.yaml).
Service REST specifications live in each `src/main/resources/openapi.yaml`.

Acceptance:

- All in-scope modules build, test, lint and satisfy coverage floors at the reviewed commit.
- Source, dependencies, secrets, licences, API contracts and release evidence pass their
  registered gates; required signatures, sign-off and PR reviews are verified separately.
- Every supported service has test scaffolding and a smoke path; the UI has unit and browser tests.
- REST and event contracts cover the exposed interfaces. A consolidated AsyncAPI document
  can satisfy the requirement; a file per topic is not itself the outcome.
- Issue/PR templates, disclosure policy, contribution and licence documentation are usable.
- Released components have attributable versions, changelogs, SBOMs and signing evidence.
- A contributor repeats the fresh-clone setup and submits a small PR using public instructions.

Proof: current CI artifacts plus a dated fresh-clone exercise. A workflow file or an old
“All builds green” sentence is not that evidence.

## M2 — Resilience primitives

**Position:** implemented foundations with continuing adoption and verification work.

Evidence: [Temporal library](../../openbank-libs-temporal/),
[outbox runtime](../../openbank-libs-runtime/src/main/kotlin/com/openbank/libs/persistence/outbox/),
[shared testing library](../../openbank-libs-testing/),
[ledger tests](../../openbank-ledger-service/src/test/) and
[outbox v2 design](../adr/0327-kernel-owned-outbox-v2.md).
The earlier bespoke saga framework is superseded by Temporal; test requirements apply to
workflow behaviour and compensation, not the retired class names.

Acceptance:

- Every event publisher commits state and outbox atomically; dispatch and redelivery preserve
  required ordering, idempotency, retry/backoff and dead-letter behaviour.
- Required multi-service flows, including account opening and payment variants, have an
  explicit orchestration model and integration tests for failure and compensation.
- Mutating operations carry the required idempotency contract and reject invalid/missing keys.
- Ledger arithmetic has property tests; integration tests exercise real persistence boundaries.
- Coverage never decreases. The original ≥70% application/domain objective remains a target;
  the enforceable floors and money-path policy are in
  [governance rules](../../openbank-libs/governance/rules.yaml), not this document.
- Fault injection and replay produce no lost/duplicate bookings; reconciliation reports no
  unexplained discrepancies, with coverage and execution evidence attached.

The presence of the shared v2 repository does not certify every service's migration. Its ADR
metadata and implementation currently differ; verify adoption per component.

## M3 — Compliance evidence

**Position:** control implementations and mappings present; external acceptance remains scoped work.

Evidence: [compliance documentation](../compliance/), [control matrix](07-compliance-matrix.md),
[audit service](../../openbank-audit-service/), [PSD2 service](../../openbank-psd2-service/),
[SCA service](../../openbank-sca-service/) and [FAPI self-assessment](../compliance/fapi2-self-assessment.md).

Acceptance:

- Every applicable control maps to a concrete, current verification artifact and owner.
- PSD2 AISP/PISP and SCA flows pass the applicable conformance exercises; no bypass paths.
- Audit integrity is demonstrated with tamper detection, append-only handling and checkpoint evidence.
- Data-subject export/erasure and retention cover the participating services end to end.
- AML monitoring demonstrates at least five reference scenarios/rules in its actual owning
  components; a fraud rule count is not a substitute for AML coverage.
- Incident reporting produces persisted, reviewable sample evidence; card-data scope and
  network segmentation have explicit verification.
- Evidence freshness is visible to operators; an independent reviewer evaluates the package.

No source review alone establishes legal compliance or regulator acceptance. The old assertion
that the net-settlement ledger leg was absent is superseded by
[ADR-0281](../adr/0281-net-settlement-ledger-leg.md) and its implementation.

## M4 — Observability and operations

**Position:** configured monitoring and exercise lanes; verify their reach and recent outcomes.

Evidence: [observability components](../../openbank-infra/gitops/components/observability/),
[synthetic journeys](../../.github/workflows/synthetic-journeys.yml),
[browser synthetic](../../.github/workflows/admin-ui-browser-synthetic.yml),
[performance gate](../../.github/workflows/perf-gate.yml),
[performance baseline](../../.github/workflows/perf-baseline.yml),
[restore workflow](../../.github/workflows/dr-restore-verify.yml) and [DR log](../bcp/dr-test-log.md).

Acceptance:

- Services emit usable traces, metrics and logs; dashboards expose traffic, errors, latency
  and saturation. Coverage is measured against the supported component inventory.
- SLOs and burn-rate alerts reflect service tiers; each actionable alert has a runbook.
- Synthetic journeys exercise login, balances and payment initiation with safe test data.
- At least five scoped failure experiments have safe execution/abort procedures and results.
- Top incident scenarios have runnable procedures; quarterly tabletop/drill evidence is recorded.
- Performance results satisfy the declared [workload and latency targets](06-scalability-targets.md)
  for the tested scope, with provenance and explicit gaps.

The restore workflow is scheduled quarterly and manually dispatchable. A recorded ledger
restore and a scheduled job do not prove fleet recovery, regional failover or measured RPO.

## M5 — Security baseline

**Position:** automated controls and evidence present; no blanket certification claimed.

Evidence: [security policy](../../SECURITY.md), [security baseline](04-security-baseline.md),
[evidence pack](../compliance/evidence-pack.md), [security workflow](../../.github/workflows/security.yml),
[release verification](../../.github/workflows/verify-release-evidence.yml) and
[disclosure/bounty readiness](../runbooks/0022-vdp-bug-bounty-readiness.md).

Acceptance:

- ASVS L3 assessment has requirement-level evidence and independent review.
- Required baseline controls are verified; transport encryption and network access control
  are evaluated separately.
- Images are signed, admission verifies the relevant signatures/attestations, and releases
  carry CycloneDX SBOMs and verifiable provenance.
- A SLSA L3 claim requires assessment of the builder and provenance guarantees, not merely
  an in-toto-shaped document.
- Independent penetration testing closes critical findings and defines repeat testing.
- Disclosure and bounty readiness are documented; rewards are not promised by this roadmap.
- Applicable CIS Kubernetes benchmark and FAPI conformance results are attached and scoped
  to the actual versions and deployment under assessment.

## M6 — Multi-region active-passive

**Position:** deferred by [ADR-0186](../adr/0186-single-region-deployment-and-disaster-recovery-posture.md).
The [BCP policy](../bcp/bcp-policy.md) defines current recovery tiers.

Acceptance:

- Two-region topology with standby, PostgreSQL replication, Kafka mirroring and object replication.
- Approved DNS failover, failback and operator-executable recovery procedures.
- Full cross-service drill demonstrates RTO ≤30 minutes and RPO ≤5 minutes, including detection,
  recovery decisions and data consistency; cold-backup recovery demonstrates RTO ≤4 hours.
- Applicable threat-led testing framework and responsibilities are documented.
- A different operator repeats the recovery procedure and records the outcome.

A single-database restore is useful evidence for a prerequisite, not completion of M6.

## M7 — Multi-region active-active and scale

**Position:** longer-term target; dependent on recovery, safety and capacity proof.

Acceptance:

- Customer home-region routing, read replication, write-conflict handling and database sharding
  have documented correctness and recovery tests.
- Tier-A target (5M customers / 10M daily payments) sustained for 30 minutes without SLO breach.
- 4× peak burst for five minutes with recovery within 30 seconds; Tier-B eight-hour soak
  without resource leaks or unbounded queue growth.
- Approved Tier 2–3 failure experiments, a capacity/cost model, and independent operator validation.
- A consented public case study substantiates the independent production-deployment criterion.

These numbers are acceptance targets from the original plan, not measured platform capacity.

## Cross-cutting work and updates

Documentation, contributor onboarding, governance, funding and release maintenance continue
across milestones. [GOVERNANCE.md](../../GOVERNANCE.md) describes the actual maintainer model;
a council or foundation is not implied by a milestone number. Public launch has already happened.

For each acceptance update, record the source commit, affected scope, dated test/run artifact,
remaining limitation and reviewer. Use issues for actionable work and ADRs for changed decisions.
Do not turn old test results into present-tense deployment claims. Changes to acceptance targets
require explicit review; editing this page does not change runtime controls or governance gates.

The project distributes software; operating a bank and connecting to regulated schemes are
operator responsibilities. AI rollout follows the governed capabilities and approvals in the
agent registry, not a blanket promise of autonomous financial decisions.
