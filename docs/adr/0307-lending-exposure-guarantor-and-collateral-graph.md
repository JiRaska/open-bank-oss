---
date: 2026-09-13
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, compliance, authz, admin-ui]
summary: "Lending uses an effective-dated exposure lens on the shared context graph, keeping borrower, guarantor, collateral and valuation roles distinct while preventing double-counted or unauthorized totals."
---

# ADR-0307 — Lending exposure, guarantor and collateral graph

## Context

Credit officers and risk reviewers need to see which facilities share borrowers,
guarantors and collateral and how those links affect exposure. A relationship diagram
is useful, but a graph can double-count syndicated/shared facilities or collateral,
mix currencies and valuations, and expose another borrower's financial information.

This lens is **P3**. It follows the P0 controls and P1/P2 lenses because it combines
money-path data, multi-party financial exposure and calculated values. Its shared-stack
reuse is agreed now; release waits for calculation reconciliation and two-person review.

## Decision

We will add a `LENDING_EXPOSURE_REVIEW` lens to ADR-0303's shared `context-service`.
Lending-service and authoritative accounting/risk sources remain owners. The context
projection stores references and effective-dated observations, not a new loan book.

The ontology distinguishes borrower, co-borrower, guarantor, facility, contract,
collateral asset, collateral allocation and valuation:

- party —[BORROWER/CO_BORROWER/GUARANTOR_OF]→ facility;
- facility —[SECURED_BY]→ collateral allocation —[ALLOCATES]→ collateral;
- collateral —[VALUED_BY]→ immutable valuation observation;
- facility —[REPORTED_AS]→ authoritative exposure snapshot.

Guarantee cap, currency, seniority, percentage and validity belong on the guarantee
edge. Collateral allocation carries secured amount, currency, priority and validity.
A physical asset is one node even when allocated to several facilities. Exposure and
coverage totals are calculated only from a declared snapshot, valuation basis and FX
rate/time. Results show formula and contributors and refuse totals when required inputs
are missing, stale or inconsistent. They do not silently net, convert or double-count.

OPA checks risk/lending role, assigned portfolio or case, legal entity/borrower scope,
edge and field classification, purpose and effective date. A user allowed one facility
does not automatically see another borrower sharing a guarantor or collateral. Such a
node may appear as a protected overlap indicator only when policy explicitly permits
that aggregate; names and amounts remain hidden. Detailed expansion requires a new
decision and audit. Export and portfolio-wide search use separate approval.

Interactive traversal is two hops/request and bounded by ADR-0303. Portfolio aggregation
is an asynchronous snapshot job, never an interactive graph traversal. Query and worker
pools have quotas below payment/ledger workloads. Cache keys include portfolio, purpose,
policy version, snapshot/effective date, currency basis and projection generation.

Acceptance reconciles every displayed exposure/coverage figure to authoritative test
snapshots and covers shared collateral, partial guarantees, co-borrowers, currency
conversion, valuation expiry, corrections, cycles, merged parties, unauthorized overlap,
revocation, missing sources and 10× workload tests. Production pilot is read-only for a
named portfolio and requires human credit/risk and security review.

## Alternatives considered

- **Compute totals from visible canvas nodes:** rejected; pagination/authorization would
  make totals incomplete and manipulable.
- **Use graph paths as accounting exposure:** rejected; topology is not a valuation rule.
- **Separate lending graph store:** rejected without a measured shared-stack limitation.
- **Deliver alongside the first pilot:** rejected due to financial and cross-party sensitivity.

## Consequences

**Positive**
- Shared guarantees/collateral become explainable without copying authoritative ownership.
- Calculation provenance prevents graph topology from masquerading as exposure accounting.

**Negative**
- Correct aggregation needs effective-dated valuations, allocations and reconciliation.
- Field/aggregate filtering is complex when assets or guarantors span portfolios.

**Neutral**
- Lending decisions, limits, provisioning and ledger postings remain in their owners.

## Compliance impact

- PCI DSS: not applicable unless a source incorrectly introduces cardholder data; such fields are denied.
- DORA: batch and interactive workloads require isolation and recovery evidence.
- GDPR: guarantor/co-borrower financial links require purpose limitation and minimization.
- PSD2: not applicable — the lens grants no account or payment capability.
- CNB: calculated exposure must reconcile to authoritative reporting inputs; no new return is created.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0308](0308-effective-time-authorization-evidence-graph.md)
- [ADR-0028](0028-lending-bounded-context.md)
- [ADR-0037](0037-anacredit-credit-exposure-reporting.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)
