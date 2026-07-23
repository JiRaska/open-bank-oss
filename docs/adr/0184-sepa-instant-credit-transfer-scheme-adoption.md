---
date: 2026-07-23
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments]
summary: "openbank-sepa-instant is the SCT Inst rail: a 10s execution deadline, a synchronous pre-settlement screening gate, VoP on the outbound leg (ADR-0171), charge parity with the regular rail, and the clearing simulator as the scheme edge."
---

# ADR-0184 — SEPA Instant Credit Transfer scheme adoption

## Context

The platform already runs a regular (non-instant) SEPA rail in `openbank-sepa-payment-service`
with its full SCT lifecycle, including R-Transaction returns via pacs.004 (ADR-0111). It does not
by itself satisfy the SEPA Instant Credit Transfer (SCT Inst) scheme, which is a distinct rail with
its own rulebook and, under the EU Instant Payments Regulation (Regulation (EU) 2024/886), its own
mandatory obligations for euro-area PSPs:

- **Speed** — funds made available to the payee within **10 seconds** of the credit-transfer order,
  around the clock (24/7/365), or the order is rejected.
- **Verification of Payee (VoP)** — the payer's PSP must offer a payee-name/IBAN match check before
  the payment is authorised (the same obligation ADR-0171 addresses at the platform level).
- **Charge parity** — charges for an instant transfer may not exceed the charges for the equivalent
  non-instant transfer.
- **Daily sanctions posture** — the Regulation replaces per-transaction sanctions screening of the
  parties with a periodic (at least daily) screening of the PSP's own customers against Union
  sanctions lists, precisely because a synchronous list hit cannot be reconciled inside a 10s budget.

`openbank-sepa-instant` exists as a separate money-path service (ADR-0057 tier T0, listed in
`rules.yaml: money_path_services`) but has never had a position ADR of its own recording *why the
rail is modelled the way it is*. ADR-0104 established the production-faithful "real ISO 20022 +
in-house scheme simulator" pattern for the payment rails generally; this ADR records how SCT Inst
specifically adopts it and where the sandbox deliberately stops short of a live scheme connection.

## Decision

We adopt SCT Inst as a **first-class, separate rail** in `openbank-sepa-instant`, distinct from the
regular SEPA rail, with the following positions:

1. **The 10-second execution deadline is a domain invariant, not a nice-to-have.** Each payment
   carries an `executionTimeoutAt` derived from `sct-inst.execution-timeout-seconds` (default `10`);
   a payment that has not reached `SETTLED` by the deadline transitions to `TIMEOUT` and is rejected
   rather than left hanging. The status machine
   (`PENDING → PROCESSING → SETTLED | REJECTED | TIMEOUT | RECALLED`) makes the deadline observable.

2. **Screening is a synchronous pre-settlement gate.** Because settlement is irreversible within
   seconds, the AML/sanctions/fraud checks run *before* the payment is submitted to the scheme, in
   line with ADR-0032's synchronous-gate philosophy for payment execution. The Regulation's
   daily-customer-screening model is the intended production posture; the sandbox keeps the
   synchronous per-payment gate because it is the more conservative, easier-to-demonstrate control
   and never *under*-screens.

3. **Verification of Payee belongs on the outbound leg and is provided by `openbank-vop-service`
   (ADR-0171), not re-implemented here.** VoP is a cross-rail capability (a pure deterministic
   name-match returning the four EPC outcomes, warn-not-block, fail-open as `NO_DATA`); the instant
   rail is one of its callers rather than its owner. Wiring the VoP call into the instant
   authorisation path follows ADR-0171's rollout and is not duplicated in this service's domain.

4. **Charge parity is a policy position, not a fee engine in this service.** `openbank-sepa-instant`
   books no fees itself; any charging is owned by the fees/billing context, and the platform position
   is that an instant transfer must never be priced above the equivalent regular transfer. The
   sandbox charges nothing on either rail, which trivially satisfies parity.

5. **The in-house clearing simulator is the scheme boundary.** The rail talks to
   `openbank-clearing-simulator` through a `SchemeGatewayPort`, producing production-faithful
   behaviour up to the network edge (ADR-0104). Real scheme submission is feature-flagged off by
   default (`SCT_INST_SCHEME_SUBMISSION_ENABLED=false`): behaviour is unchanged (screening →
   PROCESSING) until a real RT1/TIPS-style connection is deliberately enabled. The simulator is the
   clean swap-point; no live scheme credentials exist in the sandbox.

## Alternatives considered

- **Extend `openbank-sepa-payment-service` to also carry the instant flow.** Rejected: the 10s
  deadline, the different settlement finality, the recall semantics and the distinct rulebook make
  it a separate aggregate with a separate SLO tier. Overloading one service would blur the two
  rails' invariants and their money-path threat models.
- **Real scheme connectivity (TIPS / RT1) in the sandbox.** Rejected as out of scope: it requires a
  reachability/participant agreement and live credentials the reference platform cannot hold. The
  simulator preserves message fidelity (real ISO 20022) without the membership, per ADR-0104.
- **Per-transaction sanctions screening as the permanent model.** Kept for the sandbox as the
  conservative default, but noted as *not* the Regulation's end-state (daily customer screening);
  recorded here so a future switch is a known, deliberate change rather than a silent divergence.

## Consequences

**Positive**
- The instant rail's defining constraints (10s, synchronous gate, VoP on outbound, charge parity)
  are written down where a reviewer, auditor or agent finds them, instead of being implicit in code.
- The scheme boundary is explicit and swappable; enabling a real network is one flag plus an adapter,
  not a rewrite.

**Negative**
- The synchronous screening gate diverges from the Regulation's daily-screening model; a production
  deployment must revisit it (and the VoP wiring) before going live on a real scheme.
- Charge parity is asserted at the platform level but not enforced by a test here, because this
  service books no fees; the guarantee lives wherever charging is eventually implemented.

**Neutral**
- SCT Inst remains a T0 money-path service with its own threat model
  (`docs/threat-models/openbank-sepa-instant.md`) and 2-approval rule.

## Compliance impact

- PCI DSS: not applicable — no card data on this rail.
- DORA:    T0 payment-execution function; RTO/RPO targets tracked in `docs/bcp/bcp-policy.md`.
- GDPR:    debtor/creditor names and IBANs are PII handled under the platform data-lifecycle policy
           (ADR-0118); no new categories introduced here.
- PSD2:    SCA/dynamic-linking obligations for the payer's authorisation are handled upstream
           (ADR-0021); this ADR does not restate them.
- CNB:     euro instant rail operated under the EU Instant Payments Regulation ((EU) 2024/886);
           real go-live is out of sandbox scope.

## References

- ADR-0104 — production-faithful payment rails: real ISO 20022 + scheme simulator
- ADR-0111 — SEPA R-Transaction returns via pacs.004 (regular rail lifecycle)
- ADR-0171 — Verification of Payee for outbound credit transfers
- ADR-0032 — synchronous sanctions/AML screening gate in payment execution
- ADR-0057 — service criticality tiers
- Regulation (EU) 2024/886 — Instant Payments Regulation
- `openbank-sepa-instant/src/main/kotlin/com/openbank/sepainstant/` — domain model, screening policy,
  scheme gateway port
