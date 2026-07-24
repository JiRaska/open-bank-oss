---
date: 2026-07-24
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, ai-agents, sca, compliance]
summary: "Settle AP2 mandate verification (VC signature chain + pure-domain constraint checks as authorization evidence) and liability, promoting ADR-0182's verification slice to accepted. Ships a standalone verifier — no fund-moving execution."
---

# ADR-0193 — AP2 mandate verification model and liability position (promotes ADR-0182)

## Context

[ADR-0182](0182-ap2-agent-payment-mandate-verification-on-the-bank-side.md) took the
position that AP2 agent-payment mandates should be verified **bank-side** as an
authorization-evidence type, and did so deliberately design-first: its decision §5
states that *no code and no execution surface ship until a follow-up ADR moves it to
`accepted` with the verification and liability model settled*. This ADR is that
follow-up. It settles two things ADR-0182 left open — **how a mandate is
cryptographically verified** and **who bears an unauthorized or mis-scoped
agent-initiated payment** — and on that basis promotes the *verification* slice
(only) to `accepted`.

It does **not** promote autonomous execution. The thing that ships under this ADR is a
standalone verifier that answers one question — "is this presented mandate valid, and
what does it authorize?" — and emits an evidence record. Nothing under this ADR moves
funds; the mandate becomes an input the payment path *may later* consult, gated by the
HITL threshold below and by a further ADR before any non-HITL debit.

AP2 mandates are W3C Verifiable Credentials carried as signed JWS. The platform already
verifies JWS signatures with the JDK's own JCA (`java.security.Signature`) in
`openbank-sca-service`'s device-approval path (ES256 / Ed25519 over X.509 SPKI keys) —
no new JOSE dependency is needed, and reusing that primitive keeps the trusted crypto
surface small.

## Decision

We will ship `openbank-ap2-service`, a standalone **mandate verifier**, and settle the
model as follows:

1. **Verification model.** A mandate is verified in two independent stages, both of
   which must pass or the mandate fails **closed**:
   - **Signature chain.** The mandate's JWS is verified with JCA (`ES256` →
     `SHA256withECDSA`, `Ed25519` → `Ed25519`) against the issuer's public key resolved
     through a `MandateKeyResolver` port. Phase 1 resolves against a configured trust
     list; the DID / issuer-registry resolver is phase 2 behind the same port.
   - **Constraint checks.** The mandate's declared constraints — payee, amount cap,
     currency, expiry, and (for a Payment mandate) single-use — are evaluated by a
     **pure-domain** function against the presented payment. Any violation
     (expired, over-cap, currency mismatch, payee mismatch) is a closed failure with an
     explicit reason. This logic holds no framework or key material and is unit-proven.
2. **Mandate-to-flow mapping** (restating ADR-0182 §1, now fixed): an **Intent
   mandate** → a scoped consent (ADR-0126); a **Cart / Payment mandate** → evidence
   *into* an SCA decision (a possible delegated-authentication exemption or a step-up),
   never an SCA replacement. The verifier produces a `MandateVerdict` carrying an
   evidence record (mandate hash, kind, issuer, resolved constraints, verdict); it does
   **not** itself decide exemption vs step-up — that stays in `openbank-sca-service`.
3. **Liability position.** For an agent-initiated payment authorized by a mandate, the
   platform applies the **existing** payment-authorization liability: a payment carrying
   a valid, in-constraint mandate + the required SCA evidence is an *authorized*
   transaction; a payment where the mandate fails verification is *unauthorized* and
   must not execute. This ADR invents no new liability rule — it states that the
   fraud-reimbursement gap the platform audit already flagged is the open item, and that
   gap MUST be closed before any **non-HITL** execution ships. Storing only the mandate
   hash + constraints (not the full VC) bounds the personal-data exposure.
4. **HITL threshold.** The verifier is evidence-only and never executes. When a future
   ADR wires mandates into execution, agent-initiated execution stays behind the
   ADR-0031 HITL queue above a configurable threshold; a valid mandate *lowers* the
   human checkpoint, it does not remove it, until the model is proven in sandbox.
5. **Governance.** `openbank-ap2-service` is agent-facing and authorizes every verify
   call through the shared ADR-0034 PDP as an `AI_AGENT` principal (deny-by-default,
   fail-closed), the same plane as `openbank-mcp-service` (ADR-0181). It is AGPL-3.0
   (ADR-0136). Because it moves no funds it is **not** a money-path service; the ADR-0030
   threat model is required before the *execution*-wiring ADR, not before this verifier.

## Alternatives considered

- **Wire verification straight into `openbank-sca-service` now.** Rejected for this
  step: sca-service is a money-path service, so the change would demand a threat model +
  two approvals and would couple an evolving external protocol into the SCA core before
  the verifier is proven. A standalone verifier lets the VC tooling mature in isolation;
  the sca-service evidence wiring is a deliberate, separately-gated follow-up.
- **Pull a JOSE/VC library (Nimbus, ssi-kit) for verification.** Rejected for phase 1:
  the JDK JCA already covers the ES256/Ed25519 signature verification the mandates use,
  and it is the exact primitive sca-service already trusts. A heavyweight VC/DID stack
  is deferred until DID resolution (phase 2) genuinely needs it — adding it now widens
  the crypto dependency surface for no phase-1 capability.
- **Keep ADR-0182 design-only and ship nothing.** Rejected: the verification and
  liability model are now concrete enough to settle, and a bank-side verifier that never
  touches the fund-moving path is the low-risk way to occupy the niche while the
  execution question stays open.

## Consequences

**Positive**
- First OSS bank-side AP2 mandate verifier actually runs, not just specified;
  complements ADR-0181 (MCP = agent talks to the bank; AP2 = agent pays through it).
- Reuses the JDK JCA primitive sca-service already trusts — no new crypto dependency.
- Non-money-path, evidence-only: real progress with a bounded risk surface.

**Negative**
- The verifier's output is only as good as key resolution; the phase-1 trust list is a
  deliberately small, configured set, and DID/issuer-registry resolution is still to
  build.
- Ties the platform to an external, still-evolving protocol; AP2 version drift is a
  maintenance cost (restated from ADR-0182).

**Neutral**
- Execution value is still deferred: the mandate does not move money until a further ADR
  closes the fraud-reimbursement gap and wires the evidence into the SCA/execution path.

## Compliance impact

- PCI DSS: not applicable — AP2 mandates carry authorization, not card PANs.
- DORA:    the AP2 protocol dependency is on the ADR-0174 register (via ADR-0182); this verifier ships with no external runtime call in phase 1 (keys are configured), so it adds no new third-party operational dependency yet.
- GDPR:    a mandate references payer and payee; the verifier stores only the mandate hash + resolved constraints, not the full credential (data minimisation), under the existing payment-record retention (ADR-0118).
- PSD2:    the mandate is evidence *into* the SCA decision, never a replacement — dynamic linking and the exemption rules still govern; this ADR ships no SCA-bypass.
- CNB:     not applicable — no new supervisory-submission surface.

## References

- ADR-0182 — AP2 agent-payment mandate verification on the bank side (this ADR promotes its verification slice)
- ADR-0031 — AI agent governance and operations (HITL)
- ADR-0034 — Authorization policy plane (OPA PDP)
- ADR-0126 — PSD2 consent lifecycle
- ADR-0136 — AGPL licensing for agent-facing services
- ADR-0171 — Verification of Payee
- ADR-0181 — MCP server for governed AI agents
- Google Agent Payments Protocol (AP2) specification
