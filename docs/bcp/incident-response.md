# OpenBank Incident Response Framework

Date: 2026-07-02
Version: 1.0
Owner: CTO / Maintainer Lead
Review cadence: Annual (before 31 January each year)
DORA reference: Regulation EU 2022/2554, Art. 10, 17, 19–20
ADR: 0146

---

## 1. Scope

This framework covers declaration, response, and record-keeping for incidents affecting any
OpenBank ICT system — the operational layer that sits between *detection* (Falco, CodeQL, Trivy,
Alertmanager) and the per-system *runbooks* (`docs/runbooks/`). A runbook tells you how to recover
one system; this document tells you who declares an incident, at what severity, how fast the
response must start, and what gets recorded.

Every declared P1/P2 incident is the trigger event for the DORA major-incident classification
process (Art. 19–20) and the source record for MTTR (ADR-0061 Phase 3).

---

## 2. Severity tiers

| Tier | Definition | Declare within | Response target |
|------|-----------|-----------------|------------------|
| **P1** | Money-path service down, authentication/authorization bypass, confirmed data exposure, active exploitation. | 15 min of detection | 30 min |
| **P2** | High-severity vulnerability with no confirmed exploitation, non-money-path service down, degraded money-path service. | 30 min of detection | 4 h |
| **P3** | Medium-severity finding, non-customer-facing degradation. | Same business day | 24 h |
| **P4** | Informational, low-severity finding, no user impact. | — | Async — tracked as a normal issue (ADR-0052), not a declared incident. |

Severity is set by whoever declares the incident, using the definitions above — not by a fixed
mapping from alert source. A single alert (e.g. an elevated error-rate canary abort) can be P1 or
P3 depending on what it actually indicates once looked at.

---

## 3. Declaration and the ICT incident register

A P1 or P2 incident is not "handled" until it has a register entry. The register entry — not the
runbook, not a Slack thread — is what starts and stops the RTO clock referenced in
[`bcp-policy.md`](bcp-policy.md) and what DORA Art. 19–20 major-incident reporting reads from.

Minimum fields per entry:

- Declaration timestamp, declared-by (human identity, or an AI agent's charter identity per
  ADR-0031 when an agent is first to detect)
- Severity (P1–P4) and the one-line reason for that severity
- Affected system(s) / service(s)
- Resolution timestamp and one-line resolution summary
- Whether it met the DORA major-incident threshold (Art. 19) and, if so, the regulator
  notification timestamp

Both declaration and resolution go through the audit chain (ADR-0086 / ADR-0133) with an actor
identity attached — the same tamper-evident chain every other regulated event in this platform
uses. There is no separate, unaudited incident log.

---

## 4. Escalation

**Honest current state, not an aspirational org chart:** this project has a bus factor of 1
(see `GOVERNANCE.md`). There is no secondary or tertiary on-call contact today. Writing an
escalation tree that names people who don't exist would be worse than writing none — it would
give a false sense of coverage to an auditor or an incoming operator reading this document.

What exists:

- **Primary:** the maintainer, paged via GoAlert (ADR-0088 on-call integration).
- **Secondary:** none. This is a known, tracked gap, not an oversight.
- **On the day a second maintainer or an operating institution takes over this platform,**
  update this section with real names/roles before relying on it — don't defer that update past
  the point a real incident needs it.

For an institution deploying OpenBank in production, this section is the first one to fill in
with your own organization's actual on-call rotation before go-live.

---

## 5. Key ceremonies

Key material is the one class of procedure rehearsed least and needed most under actual incident
pressure. Runbooks live under `docs/runbooks/key-ceremonies/`:

- **OpenBao unseal** — recovery keys are stored externally (AWS Secrets Manager
  `openbank/openbao/break-glass`), never in-cluster. See `openbank-infra` operator notes for the
  unseal procedure; this is genuine break-glass material, not for routine secret provisioning.
- **Cosign KMS signing key rotation** — quarterly, `alias/openbank-cosign-signing`.
- **Keycloak realm signing-key renewal** — coordinated with the OIDC client secret rotation
  cadence (ADR-0099 Tier 2).
- **cert-manager PKI rotation** — automatic; this runbook covers the manual-intervention path if
  automatic renewal fails.

**Requirement:** each key-ceremony runbook gets an annual dry-run, logged in the same ICT
register as a P3 (scheduled, non-incident) entry, so "we tested this" is a checkable fact, not a
claim.

---

## 6. Relationship to other documents

| Document | Covers |
|----------|--------|
| [`bcp-policy.md`](bcp-policy.md) | RTO/RPO targets per tier, technical recovery capabilities |
| [`dr-test-log.md`](dr-test-log.md) | Record of DR test executions |
| `docs/runbooks/<NNNN>-*.md` | Per-system recovery/migration procedures |
| This document | Declaration, severity, escalation, and the register that ties detection to
  recovery to the DORA reporting obligation |

---

## Revision history

| Date | Change |
|------|--------|
| 2026-07-02 | Initial version (ADR-0146) |
