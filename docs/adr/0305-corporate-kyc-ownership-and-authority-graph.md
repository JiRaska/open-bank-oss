---
date: 2026-09-13
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kyc, onboarding, authz, admin-ui]
summary: "Corporate KYC uses a bitemporal ownership-and-authority lens on the shared context graph, keeping UBO, ownership, representation and evidence distinct and requiring case-scoped access."
---

# ADR-0305 — Corporate KYC ownership and authority graph

## Context

Corporate onboarding and review require an analyst to understand legal owners, UBOs,
directors, representatives and powers of attorney. These are different legal relations
with different effective dates and evidence. A current-state customer view loses the
chain and cannot explain what was valid at an earlier decision time.

Delivery priority is **P2** after the P0 effective-time foundation and P1 bounded lenses.
It introduces multi-party personal data, recursive ownership and historical authority.

## Decision

We will add a `CORPORATE_KYC_REVIEW` lens to ADR-0303's shared `context-service`, using
the common store, query engine, policy/audit path, UI and operational controls with a
KYC-specific ontology and policy pack.

The ontology distinguishes:

- legal entity —[OWNS {percentage, shareClass}]→ legal entity or natural person;
- legal entity —[HAS_UBO {basis}]→ natural person as a reviewed determination;
- person —[DIRECTOR_OF]→ entity and person/party —[REPRESENTS {scope}]→ entity;
- authority —[SUPPORTED_BY]→ document/version and —[GRANTED_BY]→ grantor.

Each edge carries valid time, recorded time, source jurisdiction/version and evidence.
Missing/disputed values remain unknown. The graph does not infer UBO status solely from
a percentage. Direct, indirect and control-by-other-means bases remain distinct.
Ownership cycles terminate through visited-node and depth guards.

The UI supports an `effectiveAt` snapshot and identifies late-recorded evidence. It must
not render a current representative as authorized at a past date or a revoked power as
current. Indirect ownership calculations show formula, path and source percentages and
refuse a result when inputs are missing or ambiguous. KYC remains authoritative for the
case and determination.

OPA authorizes analyst, assigned KYC case, entity/root, jurisdiction, edge type, field
classification and time. Natural-person details are field-filtered. Permission for one
company does not grant every company sharing a director or professional representative.
Expansion beyond the reviewed group needs explicit action and a new decision. Export is
separately approved, expiring and audited.

Interactive expansion is at most two hops per request and six levels per exploration.
Deeper ownership calculation is asynchronous and snapshot-bound. Responses expose
truncation/cycles. Professional-service hubs are aggregated. ADR-0303 work limits apply.

Acceptance covers cycles, missing percentages, totals outside 100%, overlapping validity,
late evidence, revoked powers, merged IDs, cross-case leakage, hidden intermediaries,
erasure/restriction, replay and correction. Pilot with synthetic corporate structures,
then assigned KYC reviewers without bulk export.

## Alternatives considered

- **Generic `RELATED_TO`:** rejected; it loses legal meaning, direction and effective time.
- **Automatically infer UBO:** rejected as authoritative; calculations only assist review.
- **Separate KYC graph database:** rejected until the shared store fails measured needs.
- **Current-state only:** rejected because historical authority must explain earlier decisions.

## Consequences

**Positive**
- Ownership, control and authority are explainable at a decision time.
- Typed relations avoid conflating owners, directors, UBOs and representatives.

**Negative**
- Bitemporal corrections and recursive calculations add lifecycle complexity.
- Multi-party data requires field-level minimization and strict case scoping.

**Neutral**
- KYC reviewers still make determinations in the owning service.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is required.
- DORA: test rebuild and capacity isolation; KYC sources remain available if this view fails.
- GDPR: purpose, access, correction, restriction, retention and deletion propagation apply.
- PSD2: representation here creates no payment mandate or account-access consent.
- CNB: effective-time provenance supports review; the graph alone is not claimed sufficient.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0308](0308-effective-time-authorization-evidence-graph.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)
