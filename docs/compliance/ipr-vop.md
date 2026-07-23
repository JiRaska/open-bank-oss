# Instant Payments Regulation (IPR) — compliance position

> Regulation (EU) 2024/886 of 13 March 2024 amending Regulations (EU) No 260/2012 and
> (EU) 2021/1230 as regards instant credit transfers in euro ("the IPR").
>
> **This is technical positioning, not legal advice.** It maps selected IPR obligations to
> the concrete services and code in this repository and is deliberately honest about what is
> **not** yet implemented. Operators must run their own compliance review with qualified legal
> counsel and the competent authority (CNB for the Czech jurisdiction). Verified against
> `origin/main`; every service, ADR and config key cited below exists in the tree.

## Scope of this note

The IPR layers instant-payment obligations on top of the SEPA Regulation for PSPs that already
offer standard SEPA Credit Transfers (SCT) in euro. This note covers the four obligations the
platform touches in code today:

1. Verification of Payee (VoP) on outbound credit transfers
2. SCT Inst 10-second execution timing
3. Charge parity between SCT Inst and standard SCT
4. Sanctions-screening cadence

It does **not** claim conformance for reachability mandates, the fraud-reimbursement /
liability-shift regime, or the PSP self-attestation duties — see [Known gaps](#known-gaps-honest).

## Requirement → implementation

| IPR obligation | What the regulation requires | Implementation in this repo | Service / ADR | Status |
|---|---|---|---|---|
| **Verification of Payee** (Art. 5c) | Before a credit transfer is authorised, the payer's PSP must check the payee name against the account identifier (IBAN) and flag a mismatch to the payer. | `openbank-vop-service` runs a pure, deterministic name-match policy returning the four EPC outcomes `MATCH` / `CLOSE_MATCH` / `NO_MATCH` / `NO_DATA`. It **warns rather than blocks** and **fails open** as `NO_DATA` when data is unavailable. | `openbank-vop-service`, [ADR-0171](../adr/0171-verification-of-payee-for-outbound-credit-transfers.md) | Partial — service live; advisory-only |
| **SCT Inst 10-second timing** (Art. 5a) | The payee's account must be credited (or the payer informed of rejection) within 10 seconds of the payment order, at any time of day. | `SctInstPaymentService` arms an execution deadline (`executionTimeoutAt = now + timeoutSeconds`) when the payment is cleared, and drives a dedicated `TIMEOUT` terminal state via `SctInstPaymentTimeout`. The window is `openbank.sct-inst.execution-timeout-seconds`, **default `10`**. | `openbank-sepa-instant` | Implemented (against the in-house scheme simulator; no live interbank leg) |
| **Charge parity** (Art. 5b) | Charges for an instant credit transfer must not exceed the charges for a non-instant (standard) SCT. | Both rails build the pacs.008 with the same scheme-level charge bearer `ChargeBearer.SLEV` in their `SchemeGatewayAdapter`, so the network-side charging model is identical across SCT and SCT Inst. | `openbank-sepa-instant`, `openbank-sepa-payment` | Partial — charge-bearer parity is modelled; see gap note below |
| **Sanctions screening** (Art. 5d) | PSPs must verify at least daily whether their own payment-service users are listed persons/entities, immediately after a listing change — rather than screening every individual instant transaction. | The sanctions list data is refreshed on an operator-configurable schedule (`SanctionsListService` holds `cronHour` / `cronMinute` / `cronDays`). Instant payments **additionally** call a synchronous per-payment screen (`POST /api/v1/sanctions/screen`) via `SanctionsScreeningPort`, which **fails closed** (`ADR-0032 §C`) — a stricter posture than the IPR baseline. | `openbank-sanctions-service`, `openbank-sepa-instant` | Implemented (both cadences present) |

### Notes on the evidence

- **VoP outcomes** are the EPC-standard set enumerated in the vop-service domain model
  (`MATCH`, `CLOSE_MATCH`, `NO_MATCH`, `NO_DATA`). The fail-open-as-`NO_DATA` behaviour is a
  deliberate contrast with the sanctions gate, which fails closed — VoP is designed to inform the
  payer, not to hard-block a transfer.
- **Timing** is enforced against the in-house scheme simulator (see
  [ADR-0104](../adr/0104-production-faithful-payment-rails-iso-20022-and-scheme-simulator.md)); the
  platform is production-faithful up to the network boundary but has no live interbank connection,
  so real end-to-end sub-10-second SLA cannot be asserted here.
- **Charge parity** is asserted only at the ISO 20022 charge-bearer level (`SLEV` on both rails).
  This repository does **not** contain a per-rail tariff in `openbank-billing-service` that proves
  the *priced* SCT Inst fee equals the SCT fee; that pricing parity is a billing-configuration and
  operational control, not something enforced in code today.
- **Sanctions cadence**: IPR Art. 5d deliberately moves the primary control to *daily screening of
  the PSP's own client base* rather than per-transaction screening. This platform does both — a
  scheduled list refresh plus a synchronous per-payment screen on the instant rail. The
  per-transaction screen is an additional control, not a substitute for the daily client-base
  screening obligation, which remains an operational duty.

## Known gaps (honest) <a name="known-gaps-honest"></a>

The following IPR obligations are **not** implemented in this repository and must not be presented
as covered:

- **Fraud reimbursement / liability shift.** The IPR's VoP regime carries a liability consequence:
  where a PSP fails to provide a correct VoP result and the payer suffers loss, the PSP may be
  liable to reimburse. No reimbursement, liability-shift, or VoP-result-linked refund workflow
  exists in the codebase. VoP here is advisory only.
- **Reachability (send + receive) mandate.** IPR requires PSPs already offering SCT to be reachable
  for both sending and receiving SCT Inst by the regulatory deadlines. The platform settles against
  an internal scheme simulator with no live interbank reachability, so this obligation is out of
  scope for the current milestone.
- **Priced charge parity in billing.** As above — parity is modelled at the scheme charge-bearer
  level, not proven by a billing tariff.
- **PSP self-attestation / competent-authority reporting** of IPR conformance is a process
  obligation with no code artefact and is not addressed here.

## References

- [ADR-0171 — Verification of Payee for outbound credit transfers](../adr/0171-verification-of-payee-for-outbound-credit-transfers.md)
- [ADR-0104 — Production-faithful payment rails: real ISO 20022 + scheme simulator](../adr/0104-production-faithful-payment-rails-iso-20022-and-scheme-simulator.md)
- [ADR-0111 — SEPA R-Transaction returns via pacs.004](../adr/0111-payment-r-transaction-returns-pacs004.md)
- [ISO 20022 message catalog](iso-20022-catalog.md)
- [Compliance matrix](../strategy/07-compliance-matrix.md)
