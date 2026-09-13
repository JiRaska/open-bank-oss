---
date: 2026-07-23
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, ai-agents, sca]
summary: "Verify AP2 signed agent-payment mandates (Intent/Cart/Payment VCs) bank-side as an authorization-evidence type in the payment path, mapping mandate types onto existing consent and SCA — design-first, no execution until proven."
---

# ADR-0182 — AP2 agent-payment mandate verification on the bank side

> **The follow-up this ADR requires EXISTS: [ADR-0193](0193-ap2-mandate-verification-model-and-liability-position-promotes-adr-0182.md)**
> (accepted, 2026-07-24). §5 below says no code ships under this ADR "until a follow-up ADR moves
> it to `accepted` with the verification and liability model settled" — ADR-0193 is that ADR, and
> the verifier shipped under it: `openbank-ap2-service` is built (v0.3.1) and deployed
> (`gitops/apps/ap2.yaml`, OPA sidecar, network policies). Neither ADR pointed at the other, so a
> reader arriving here had no way to discover the decision had been settled and built. This note
> is that pointer, added 2026-08-19; the registry's `supersedes`/`superseded-by` fields are not
> used because 0193 *promotes a slice of* this ADR rather than replacing it, which the schema has
> no field for. This ADR's own status stays `proposed`/`planned` deliberately: what shipped is
> 0193's verification slice, and the execution surface §2/§4 describe (sca-service wiring, the
> HITL threshold on a real payment) has not.

## Context

Agentic payment protocols moved from proposal to ecosystem in 2025–2026.
Google's Agent Payments Protocol (AP2) has 60+ backers including Mastercard,
PayPal, Amex, Adyen and Worldpay, defines three signed mandates
(Intent → Cart → Payment) carried as verifiable credentials, and ships
reference implementations (including Kotlin). Parallel efforts exist
(OpenAI/Stripe ACP, Stripe's Machine Payments Protocol), but AP2 has the
broadest scheme backing.

Almost all of that work is on the *agent* and *merchant* side. The
**account-servicing / PSP side — verifying a presented mandate before moving
funds — is an unoccupied niche**, and it is exactly where a bank platform's
authorization evidence lives. This platform already has the primitives an AP2
verifier needs: `openbank-sca-service` (strong customer authentication and
dynamic linking), `openbank-consent-service` (ADR-0126), SEPA Instant execution
(`openbank-sepa-instant`), and Verification of Payee (ADR-0171). ADR-0181 will
expose an MCP surface but deliberately stops at the HITL `propose_payment`
flow; autonomous agent-initiated execution needs a stronger authorization
model, which is what an AP2 mandate provides.

The risk is obvious: a mandate is a delegated authority to move a customer's
money without a human at the moment of payment. The whole ADR is about the
evidence and limits that make that acceptable, and it is deliberately
design-first — no execution surface ships until the verification and liability
model is proven.

## Decision

We will treat AP2 as an **authorization-evidence type in the payment path**,
not a new payment rail, and specify it before building it:

1. **Mandate-to-flow mapping.** Map AP2 mandate types onto existing flows: an
   **Intent mandate** (the customer's standing authority for an agent to
   transact within limits) maps onto a scoped consent (ADR-0126); a **Cart /
   Payment mandate** (this specific transaction) maps onto an SCA outcome —
   either a delegated-authentication exemption evidenced by the mandate, or a
   step-up, decided per the mandate's constraints and the amount.
2. **Verification.** `openbank-sca-service` (or a dedicated verifier it
   delegates to) verifies the AP2 verifiable-credential signature chain and the
   mandate constraints (payee, amount cap, expiry, currency) as an additional
   authorization-evidence record on the payment, alongside SCA and VoP. A
   mandate that fails signature or constraint checks fails the payment closed.
3. **Liability model.** The ADR records the liability position for an
   agent-initiated payment authorized by a mandate (who bears an unauthorized
   or mis-scoped transaction), tied to the existing fraud-reimbursement gap the
   platform audit already flagged — this ADR does not invent liability rules,
   it states which existing rule applies and where the gap is.
4. **HITL threshold.** Agent-initiated execution stays behind a human-in-the-
   loop step above a configurable threshold, reusing the ADR-0031 HITL queue;
   the mandate lowers, but does not remove, the human checkpoint until the model
   is proven in sandbox.
5. **Scope.** AP2 first (broadest scheme backing); ACP and Stripe MPP are
   tracked and folded in only if they stabilise and a concrete integration need
   appears. This ADR is `proposed` and design-only: no code and no execution
   surface ship under it until a follow-up ADR moves it to `accepted` with the
   verification and liability model settled.

## Alternatives considered

- **Do nothing / wait for the standards to settle.** Rejected as the default
  but not the position: AP2's scheme backing and reference implementations are
  already concrete enough that a bank-side verifier is a differentiator now, and
  designing the evidence model early avoids bolting it onto the payment path
  later. Hence `proposed`, design-first — engaged, not built.
- **Build agent payments on a bespoke internal mandate format.** Rejected: a
  proprietary mandate has no interoperability with the agent ecosystem that
  makes this worth doing; the entire value is verifying the mandates agents
  actually present.
- **Treat an AP2 mandate as sufficient to skip SCA entirely.** Rejected: the
  mandate is evidence *into* the SCA decision (a possible delegated-auth
  exemption), never a replacement for the SCA/dynamic-linking machinery that
  PSD2 requires; the amount and mandate constraints decide exemption vs step-up.

## Consequences

**Positive**
- First OSS bank-side AP2 mandate verifier; complements ADR-0181 (MCP = agent
  talks to the bank; AP2 = agent pays through the bank).
- Reuses SCA, consent, VoP and SEPA Instant rather than adding a rail; the
  mandate becomes one more authorization-evidence record on the payment.

**Negative**
- Delegated authority to move funds without a human at payment time is a
  material new risk surface; the liability and fraud-reimbursement gap must be
  closed before any non-HITL execution.
- Ties the platform to an external, still-evolving protocol; version drift in
  AP2 is a maintenance cost.

**Neutral**
- Design-first means the value is deferred until the follow-up ADR; the
  verifiable-credential tooling (VC signature verification) is new to the stack.

## Compliance impact

- PCI DSS: not applicable at this layer — AP2 mandates carry authorization, not card PANs; card-rail specifics are out of scope for this ADR.
- DORA:    a new external protocol dependency enters the ADR-0174 register; the verifier ships with a threat model (ADR-0030) before any execution surface.
- GDPR:    a mandate references the payer and payee; verification records are personal data held under the existing payment-record retention (ADR-0118). Data minimisation: store the mandate hash and constraints, not more than the payment needs.
- PSD2:    the mandate is evidence into the SCA decision, never a replacement — dynamic linking and the exemption rules still govern; an agent presenting a mandate is a delegated-authority flow, not an SCA bypass.
- CNB:     not applicable — no new supervisory-submission surface.

## References

- ADR-0031 — AI agent governance and operations (HITL)
- ADR-0126 — PSD2 consent lifecycle
- ADR-0171 — Verification of Payee
- ADR-0181 — MCP server for governed AI agents
- Google Agent Payments Protocol (AP2) specification
