---
date: 2026-07-23
decision-status: accepted
delivery-status: n-a
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [compliance]
summary: "Forward position on PSD3/PSR and FIDA: the platform's PSD2 XS2A, unified consent and strong SCA already align with much of the regime; the FIDA permission dashboard, PSR fraud sharing and API migration are deferred pending Level-2 text."
---

# ADR-0187 — PSD3 / PSR and FIDA forward-regulatory position

## Context

The EU payments framework is being recast. Three instruments matter:

- **PSD3** (Directive) and **PSR** (Payment Services Regulation) — replace PSD2, moving most conduct
  rules into a directly-applicable Regulation, strengthening SCA, tightening TPP access to
  dedicated interfaces, adding fraud-prevention duties (including IBAN/name verification for all
  credit transfers) and transaction-monitoring/information-sharing provisions.
- **FIDA** (Financial Data Access Regulation) — extends "open banking" to "open finance": customer-
  permissioned access to a broader set of financial data (savings, investments, insurance, loans)
  through standardised interfaces and, notably, a **permission dashboard** giving customers a single
  place to see and revoke every data-access grant.

These are at various stages of the legislative process, and the Level-2 technical standards (RTS/ITS)
that determine the *how* are not final. The platform should neither pretend to implement unfinished
law nor ignore its direction. It already has foundations that map onto the coming regime, and
recording where it aligns versus where it defers turns "future regulation" from a vague worry into a
tracked position.

## Decision

We adopt a **forward-regulatory position**: build on what already aligns, defer what depends on
unfinished Level-2 text, and record both explicitly.

**Already aligned (no new decision needed, tracked here for continuity):**

1. **XS2A access is standards-based.** The TPP interface is Berlin Group NextGenPSD2 XS2A with a
   Czech ČOBS profile (ADR-0090); PSD3/PSR keeps standardised dedicated interfaces, so this
   foundation carries forward rather than being thrown away.
2. **Consent is already unified and revocable.** `consent-service` is a single authority for PSD2,
   GDPR and AI-agent delegation consents with one state machine and revoke/expire events (ADR-0126).
   This is the natural substrate for a FIDA-style permission dashboard — the hard part (one consent
   model, revocation events) already exists.
3. **SCA is strong and fail-closed.** Passwordless/passkey authentication (ADR-0066) and decoupled,
   dynamically-linked SCA that never auto-approves (ADR-0021) meet the direction PSR takes on
   authentication.
4. **Payee verification exists.** VoP for outbound credit transfers (ADR-0171) anticipates PSR's
   IBAN/name-check obligation across all transfers, not just instant ones.

**Deferred (pending final text; recorded as known, not silently missing):**

5. **The FIDA permission dashboard as a customer-facing surface** is deferred: the consent data model
   is ready, but the dashboard UX, the scheme-membership/compensation arrangements FIDA envisions,
   and the standardised open-finance API shapes await final requirements.
6. **PSR fraud-data sharing / transaction-monitoring information exchange** between PSPs is deferred;
   the platform has the analytics and audit substrate (ADR-0022) but not the inter-PSP sharing
   mechanism, which depends on the final regime and market schemes.
7. **The precise PSD2→PSR API migration** (endpoint/version changes, deprecation of PSD2-specific
   constructs) waits until the Level-2 standards fix the interface details; the existing two-axis
   API-contract versioning (ADR-0048) is the mechanism through which it will land when they do.

We will **not** implement against draft text that can still change, to avoid building the wrong thing;
we will keep the foundations regime-ready.

## Alternatives considered

- **Implement a best-guess PSD3/PSR/FIDA layer now.** Rejected: the Level-2 detail (RTS/ITS, API
  standards) is not final, so an early build risks encoding requirements that change, creating
  rework and a false impression of compliance with unfinished law.
- **Ignore the coming regime until it applies.** Rejected: several foundations (unified consent,
  strong SCA, VoP) are already in place and it is cheap and honest to record how they map forward,
  so the eventual migration is an increment rather than a surprise.

## Consequences

**Positive**
- A clear map of what already anticipates PSD3/PSR/FIDA and what is deferred, so the eventual
  regulatory migration is scoped work against a known baseline, not a rediscovery.
- No wasted effort building against draft Level-2 text.

**Negative**
- The platform is not PSD3/PSR/FIDA-compliant today and does not claim to be; a real operator under
  the new regime has open items (permission dashboard, PSR fraud sharing, API migration).
- The position needs revisiting when the Level-2 standards are published; this ADR will be
  superseded or amended then.

**Neutral**
- No code changes accompany this ADR; it is a decision-only position (`delivery-status: n-a`).

## Compliance impact

- PCI DSS: not applicable.
- DORA:    not applicable to this scope decision.
- GDPR:    FIDA data-access consent overlaps GDPR lawful-basis and the unified consent model
           (ADR-0126); no new processing introduced here.
- PSD2:    current baseline is PSD2 XS2A (ADR-0090); PSD3/PSR supersede PSD2 and are the subject of
           this forward position, not yet implemented.
- CNB:     the Czech competent authority will supervise the PSD3/PSR/FIDA transposition and
           application; no filing is made on the basis of this ADR.

## References

- ADR-0090 — PSD2 XS2A: Berlin Group NextGenPSD2 + ČOBS Czech profile
- ADR-0126 — unified consent lifecycle and GDPR linkage
- ADR-0021 — decoupled, dynamically-linked SCA (no auto-approve)
- ADR-0066 — passwordless customer authentication
- ADR-0171 — Verification of Payee for outbound credit transfers
- ADR-0048 — decouple API-contract version from service release version
- PSD3 / PSR proposals (COM/2023/366, COM/2023/367); FIDA proposal (COM/2023/360)
