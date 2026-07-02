# ADR-0146 — Incident response and security-operations framework

Date: 2026-07-02
Decision-Status: Proposed   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): jiri.raska

## Context

`docs/runbooks/` holds per-system operational procedures (Vault/OpenBao,
Postgres, Kafka, Loki, per-service runbooks) and ADR-0134 sets BCP/DR
targets (RTO/RPO per tier, DR test cadence) with an ICT-risk-management
mapping to DORA. What sits between the two is missing: a runbook says *how*
to recover one system; ADR-0134 says *how fast* recovery must be for the
platform. Neither says *who declares an incident, at what severity, who is
paged, in what order, and when the clock in ADR-0134's RTO actually starts
running*. Without a severity/escalation decision, "detection" (Falco, Trivy,
CodeQL findings, Alertmanager) and "response" (the runbooks) are connected
only by tribal knowledge — which does not survive a bus-factor-1 project
(see `GOVERNANCE.md`) and does not produce the audit trail DORA's major-ICT-
incident reporting (Art. 19–20) requires: a reportable incident needs a
recorded classification decision and a timeline, not just a fixed runbook
followed at some point.

## Decision

We will adopt a four-tier incident severity model with a recorded
escalation path, and treat every declared incident as a first-class,
auditable record:

- **P1** — money-path service down, authentication/authorization bypass,
  confirmed data exposure, active exploitation. Declare within 15 minutes
  of detection; initial response target 30 minutes.
- **P2** — high-severity vulnerability with no confirmed exploitation,
  non-money-path service down, degraded money-path service. Response target
  4 hours.
- **P3** — medium-severity finding, non-customer-facing degradation.
  Response target 24 hours.
- **P4** — informational, low-severity finding, no user impact. Async,
  tracked as a normal issue (ADR-0052).

Every P1/P2 incident opens an entry in the ICT incident register (the same
register ADR-0134 already requires for DORA reporting and ADR-0061 already
plans to source MTTR from); the register entry, not the runbook, is what
starts and stops the RTO clock. Declaration and resolution both go through
the audit chain (ADR-0086/0133) with an actor identity — human or, per
ADR-0031, an AI agent's charter identity when an agent is first to detect.
Key-handling procedures (OpenBao unseal, cosign KMS key rotation, Keycloak
realm signing-key renewal, cert-manager PKI rotation) are written down as
runbooks under `docs/runbooks/key-ceremonies/` with an annual dry-run
requirement, since they are the one class of procedure that is rehearsed
least and needed most under actual incident pressure.

## Alternatives considered

- **Leave severity classification implicit / ad hoc per incident.**
  Rejected — this is the status quo; it produces inconsistent response times
  and, more importantly, no defensible record for a DORA major-incident
  report, which has its own regulator-facing deadline once an incident is
  classified as major.
- **Adopt a third-party IR platform/process (e.g. a commercial on-call SaaS
  runbook product) wholesale.** Rejected — GoAlert (already in the
  observability stack per ADR-0088) already provides the paging primitive;
  the missing piece is the severity/escalation *decision*, not another tool.
- **Fold this into ADR-0134 as an addendum rather than a new ADR.**
  Rejected — ADR-0134 is about business-continuity *targets*; this ADR is
  about the *operational decision process* that hits those targets. Keeping
  them separate lets each evolve independently (targets change on a
  regulatory cadence; escalation mechanics change on an operational one).

## Consequences

**Positive**
- Gives DORA Art. 17/19–20 major-incident reporting a concrete trigger
  (P1/P2 declaration) and a concrete evidence trail (the ICT register entry
  + audit chain), instead of relying on someone remembering to write it up
  after the fact.
- Directly feeds ADR-0061's DORA-metrics roadmap: MTTR needs incident-
  register sourcing (its Phase 3, currently deferred) — this ADR is the
  decision that Phase 3 has been waiting on.
- Key-ceremony documentation reduces bus-factor risk on the single highest-
  consequence class of manual procedure in the platform.

**Negative**
- At a single-maintainer bus factor, a formal escalation tree is partly
  aspirational — the honest version of this ADR states that today's
  secondary/tertiary escalation contacts are unfilled, rather than
  fabricating an on-call rotation that does not exist. That gap is itself
  visible and trackable once written down, which is the point.
- Adds process overhead (register entry, severity call) to every P1/P2,
  which is friction during an actual incident if not rehearsed.

**Neutral**
- Does not replace or duplicate any existing runbook; runbooks remain the
  system-level "how," this ADR is the incident-level "who/when/how fast."

## Compliance impact

- PCI DSS: Req. 12.10 (incident response plan, roles, and testing).
- DORA: Art. 10 (ICT incident detection), Art. 17 (ICT-related incident
  management process), Art. 19–20 (major incident classification and
  reporting).
- GDPR: Art. 33–34 (breach notification) share the same declaration/
  classification trigger for incidents involving personal data.
- PSD2: RTS on incident reporting for payment service providers — the same
  classification event this ADR introduces is the trigger for the PSD2
  major-incident notification obligation.
- CNB: aligns with ČNB's expected incident-management and reporting
  practice for regulated entities.

## References

- ADR-0134 (business continuity and DORA ICTRM) — RTO/RPO targets and the
  ICT incident register this ADR operationalizes.
- ADR-0061 (DORA metrics from in-house sources) — MTTR Phase 3 sourcing,
  unblocked by the register this ADR requires.
- ADR-0086 / ADR-0133 (non-repudiation, tamper-evident audit chain) — the
  audit mechanism incident declarations are recorded through.
- ADR-0031 (AI agent governance) — how an agent-detected incident is
  attributed.
- ADR-0088 (observability extension: on-call, SLO-as-code) — GoAlert paging
  primitive reused here.
- `GOVERNANCE.md` — bus-factor context for the escalation-tree honesty note.
