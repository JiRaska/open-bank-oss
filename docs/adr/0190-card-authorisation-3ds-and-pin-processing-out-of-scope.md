---
date: 2026-07-24
decision-status: superseded
delivery-status: n-a
authors: [Jiri Raska]
supersedes: []
superseded-by: [0283]
delivery-repos: []
tags: [architecture, payments]
summary: "Card authorisation, 3DS/SCA and PIN processing are out of scope: the platform is a card issuer (registry, lifecycle, synthetic PANs, ADR-0113), not a processor; a real deployment adds a licensed processor behind a rail-style port."
---

# ADR-0190 — Card authorisation, 3DS and PIN processing out of scope

## Context

`openbank-card-issuance-service` (ADR-0113) is a **card registry and lifecycle manager**: it owns the
card state machine, mints synthetic sandbox PANs, holds reference-only spending limits and emits two
outbox events. ADR-0113 states, in one line, that "authorisation, 3DS and PIN stay out of scope" —
but the *card-processing boundary* has never been recorded as its own decision. Where online
authorisation, 3-D Secure / SCA and PIN verification sit is exactly the question a payments reviewer
or an auditor asks of a banking platform, and a boundary stated only as an aside inside another ADR
reads as an omission rather than a position.

The forces:

- **Regulatory / scheme scope.** Online card authorisation, the 3-D Secure ACS (issuer Access Control
  Server) and PIN verification are card-scheme functions (Visa/Mastercard) performed by a **licensed
  issuer-processor**, under scheme certification and full PCI DSS scope for the authorisation data
  path (PAN + sensitive authentication data). None of that is reference-implementation value — it is
  a multi-quarter certification programme against private scheme specifications.
- **No real money, no real PANs.** The platform issues **synthetic** PANs (ADR-0113); there is no
  cardholder to authenticate, no acquirer traffic to authorise, and no real PAN or SAD to protect.
- **The absence is intentional but unrecorded.** As with treasury (ADR-0185) and crypto/CBDC
  (ADR-0188), the boundary is deliberate; this ADR records it and — importantly — the *shape* a real
  integration would take, so it is not treated as impossible.

## Decision

We record that **online card authorisation, 3-D Secure / SCA (ACS) and PIN processing are out of
scope**, deliberately, with a defined integration boundary:

1. **The platform is a card issuer, not a card processor.** `openbank-card-issuance-service` stays the
   registry and lifecycle owner (ADR-0113). It does **not** authorise transactions online, run an
   issuer ACS for 3DS challenges, or verify PINs/PVV/CVV.
2. **A real deployment integrates a licensed issuer-processor** (or scheme-certified BIN sponsor)
   **behind a rail-style port**, the same pattern used for clearing and SWIFT — the authorisation
   request/response, 3DS ACS callbacks and PIN/HSM operations live behind that adapter, never inside
   the ledger or the issuance service.
3. **Reference spending limits stay reference.** The limits in card-issuance are advisory metadata for
   the registry, not an online authorisation control; nothing in the platform declines a live card
   transaction, because there are none.
4. **PCI DSS authorisation-data scope stays out.** Because no PAN + SAD authorisation path exists, the
   platform carries no card-authorisation PCI scope; the synthetic PANs are protected as ordinary
   sensitive data (ADR-0189 field-level protection), not as live cardholder data.

## Alternatives considered

- **Build an issuer-processor / authorisation engine in-platform.** Rejected: online authorisation,
  scheme certification and the full PCI DSS authorisation-data path are a certification programme, not
  a reference-architecture decision; it would add enormous PCI scope for synthetic traffic that has no
  acquirer and no real cardholder.
- **Embed an issuer 3DS ACS.** Rejected: an ACS requires scheme (EMV 3DS) certification and a real
  cardholder-authentication relationship; with synthetic PANs there is nothing to challenge.
- **Leave the boundary implicit inside ADR-0113 (status quo).** Rejected: the card-processing position
  is a first-order question for a banking platform; recording it explicitly (this ADR) closes the
  corpus gap the 2026-07-16 platform audit flagged.

## Consequences

**Positive**
- The card-processing boundary is now an explicit, citable position, not an aside.
- PCI DSS scope stays honestly bounded: no live authorisation data path to protect.
- The rail-style-port shape means a future processor integration is a known, additive move.

**Negative**
- The platform cannot demonstrate an end-to-end card *authorisation* (a live "card presented →
  approved/declined" flow); card behaviour ends at issuance + lifecycle + reference limits.

**Neutral**
- ADR-0113's issuance registry, synthetic PANs, reference limits and outbox events are unchanged; this
  ADR records their boundary, it does not alter them.

## Compliance impact

- **PCI DSS:** not applicable to an authorisation-data path — none exists (synthetic PANs, no PAN+SAD
  online authorisation). Synthetic PANs are protected under ADR-0189, not as live cardholder data.
- **PSD2:** SCA for card payments (3DS) is the issuer-processor's ACS function, out of scope here; the
  platform's strong customer authentication covers its own PSD2 XS2A surface, not card authorisation.
- **DORA:** not applicable — no card-authorisation ICT service is operated.
- **GDPR:** not applicable beyond the synthetic-PAN protection already covered by ADR-0189.
- **CNB:** not applicable — no card-scheme authorisation activity is performed.

## References

- ADR-0113 — Card issuance bounded context (registry, lifecycle, synthetic PANs; authorisation/3DS/PIN
  declared out of scope — this ADR records that boundary as its own decision).
- ADR-0185 — Treasury and liquidity management out of scope (same "deliberate absence, recorded" shape).
- ADR-0188 — Crypto-assets (MiCA) and CBDC out of scope (rail-style-port integration boundary pattern).
- ADR-0189 — Field-level encryption and tokenization for PII and PAN (how synthetic PANs are protected).
- `docs/audits/2026-07-16-platform-audit.md` §3.3 — the corpus gap analysis that requested this ADR.
