---
date: 2026-07-02
decision-status: accepted
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, testing, kubernetes]
summary: "Adopt Chaos Mesh for infrastructure failure injection, sandbox-only for now, with every experiment declaring a hypothesis and abort condition, run on a monthly rotation and recorded in the ICT register as DORA evidence."
---

# ADR-0151 — Chaos engineering and infrastructure failure-injection policy

## Context

The deterministic simulation testing harness (ADR-0100/0115) validates
money-path *code* under injected faults — stuck sagas, compensation gaps,
double-credit — inside a deterministic, replayable JVM process. It does not
and cannot validate the *infrastructure* underneath that code: a CNPG
primary failover, a Kafka broker loss, an EKS node eviction mid-transaction,
or a Temporal server restart. ADR-0011 (testing pyramid) names chaos testing
as a future layer ("M4 onwards" for staging, "M7 onwards" for production-
grade) but sets no policy — no tooling choice, no blast-radius rule, no
approval gate, no link to the evidence DORA's resilience-testing
requirement (Art. 24–26) expects. Today, whatever infrastructure resilience
the platform has is the accidental byproduct of Karpenter's normal node
consolidation churn, not a deliberately tested property.

This is explicitly a complement to DST, not a substitute: DST proves the
code handles a fault correctly when the fault happens; chaos engineering
proves the fault actually gets triggered and observed correctly by the real
infrastructure DST does not model (NetworkPolicy behavior under partition,
PodDisruptionBudget honoring, actual failover timing).

## Decision

We will adopt Chaos Mesh (CNCF, Kubernetes-native, fits the existing
cloud-agnostic in-cluster substrate per ADR-0027) as the chaos-engineering
tool, with the following policy:

- **Sandbox-only to start.** Production chaos experiments are explicitly
  out of scope until ADR-0011's M7 milestone and a separate approval gate
  this ADR does not itself grant.
- **Every experiment declares a hypothesis and an abort condition** before
  it runs (e.g. "ledger-service survives a CNPG primary failover with < 30s
  of write unavailability; abort if error rate exceeds 50% for > 2
  minutes") — an experiment without a stated hypothesis is not run.
- **Scheduled, not continuous**: a monthly chaos run against a declared
  rotation of failure classes (pod kill, node drain, network delay/
  partition, CNPG failover, Kafka broker loss), starting only after the
  Temporal migration (ADR-0101/0120) reaches its target end state — running
  chaos against a topology mid-migration would test a shape of the system
  that is about to change, wasting the exercise.
- **Every run's result is recorded in the ICT incident/resilience-testing
  register** (the same register ADR-0146 introduces), which is what turns
  these runs into DORA Art. 24–26 evidence rather than ad hoc exercises.

## Alternatives considered

- **LitmusChaos instead of Chaos Mesh.** Rejected as the default choice —
  both are viable CNCF options; Chaos Mesh's dashboard and CRD model fit
  the existing ArgoCD/GitOps-declarative pattern slightly more naturally,
  and this is an implementation detail, not a decision with lasting
  consequences either way.
- **Rely on DST alone and treat infrastructure resilience as adequately
  covered by Karpenter's natural node churn.** Rejected — natural churn is
  uncontrolled (no hypothesis, no fixed blast radius, no scheduled
  cadence) and does not exercise network-partition or stateful-failover
  scenarios DST cannot reach either.
- **Start chaos experiments in production immediately (fail-fast
  philosophy).** Rejected — appropriate for a company with mature
  incident response and a large blast-radius budget; OpenBank does not yet
  have the incident-response framework (ADR-0146) or bus-factor resilience
  to safely absorb a production chaos experiment gone wrong today.

## Consequences

**Positive**
- Turns "infrastructure resilience" from an assumed property into a
  measured, scheduled, evidenced one — directly closing a DORA testing-
  programme gap this review found.
- The hypothesis/abort-condition discipline (borrowed from the general
  chaos-engineering practice, e.g. Netflix's original formulation) prevents
  chaos runs from becoming unstructured production-adjacent risk-taking.

**Negative**
- Explicitly deferring to after the Temporal migration and to sandbox-only
  means real infrastructure-resilience evidence is not available
  immediately — this is a deliberate sequencing choice, not a gap this ADR
  closes today.
- Chaos Mesh itself is another operational component to run, monitor, and
  keep patched (its own supply-chain surface under ADR-0030).

**Neutral**
- Does not change DST's scope or ownership (ADR-0100/0115 remain the
  code-level fault-injection layer).

## Compliance impact

- PCI DSS: not applicable directly.
- DORA: Art. 24–26 (digital operational resilience testing programme) —
  the primary regulation this ADR is written to satisfy.
- GDPR: not applicable.
- PSD2: not applicable directly.
- CNB: aligns with ČNB supervisory expectation of demonstrated operational
  resilience testing.

## References

- ADR-0100 / ADR-0115 (deterministic simulation testing / harness) — the
  code-level complement this ADR does not duplicate.
- ADR-0011 (testing pyramid) — names chaos as a future layer without policy;
  this ADR supplies that policy.
- ADR-0101 / ADR-0120 (Temporal durable execution / migration) — the
  topology this ADR's scheduled runs wait to stabilize against.
- ADR-0027 (cloud-agnostic in-cluster substrate) — why Chaos Mesh fits the
  existing GitOps model.
- ADR-0146 (incident response and security-operations framework) — the
  register chaos-run results are recorded into.
