---
date: 2026-09-13
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fraud, aml-sanctions, authz, admin-ui]
summary: "Fraud and AML investigators use separate purpose-bound lenses over the shared context graph to examine explainable device, counterparty and money-flow relationships without turning similarity into evidence."
---

# ADR-0304 — Fraud and AML relationship investigation

## Context

Fraud and AML investigators need to find shared devices, beneficiaries, counterparties
and the time-ordered flow of funds. Existing transaction scoring (ADR-0084) and
synchronous screening (ADR-0032) decide individual operations; neither is a historical
network-investigation store. A broad graph can reveal unrelated customers and is one of
the most sensitive views in the bank.

This lens has critical business value, but its cross-customer scope makes it unsuitable
as the first real-data pilot. Its delivery priority is **P2**, after ADR-0308's effective-
time authorization/audit foundation and the bounded P1 lenses have proved isolation,
revocation and workload controls. Priority is not permission to bypass those gates.

## Decision

We will implement separately authorized `FRAUD_INVESTIGATION` and `AML_INVESTIGATION`
lenses on the shared `context-service` stack defined by ADR-0303. They reuse the
canonical model, ingestion, PostgreSQL store, policy/audit layer and admin UI explorer.
They do not get their own graph database.

The initial ontology is:

- party —[USED]→ device/session and party/account —[SENT_TO]→ beneficiary account;
- payment —[DEBITED_FROM/CREDITED_TO]→ account, carrying amount, currency and event time;
- party —[SUBJECT_OF]→ fraud/AML case and case —[SUPPORTED_BY]→ evidence reference;
- sanctions/screening results as observations, never identity or guilt edges.

Source IDs and directed transaction legs are authoritative references. Device matches
are observations with confidence and collection method. Shared IP, address, device or
payee never merges parties, creates a fraud hold or raises an AML decision automatically.
Hypotheses are structurally separate from facts and retain algorithm/model version.
Amounts group by currency or use a disclosed rate and valuation time; unlike currencies
are never summed silently.

Every request supplies a verified open case and one purpose. OPA authorizes the
investigator, assignment, root, edge classes, time window and fields. Fraud permission
does not imply AML permission. Search, counts, path discovery, facets, export and
semantic retrieval use the same allowed subgraph. Expanding to another customer needs
an allowed evidential edge and a new policy decision. Results carry case, policy version
and snapshot time.

Interactive queries use one hop per expansion and at most two hops per request. Default
windows are 30 days for fraud and 90 days for AML; wider retained evidence requires an
explicit reason. High-degree merchants/infrastructure are aggregated first. Long
money-flow tracing runs asynchronously against a fixed snapshot and returns bounded,
explainable evidence.

The lens is read-only. Decisions, holds, reports and case transitions remain operations
of their owning services with existing approvals. Semantic search may retrieve similar
authorized closed-case evidence, but similarity cannot create a link, guilt score or action.

Acceptance covers cross-case/customer IDOR, hidden-node path leakage, revoked assignment,
unavailable OPA/audit, timing/count side channels, high-degree nodes, replay, duplicates
and currency-safe aggregation. Measure task precision/recall and false associations as
well as latency. Pilot on synthetic data, then a limited investigator group without export.

## Alternatives considered

- **One financial-crime permission:** rejected; fraud and AML have different purposes,
  teams, evidence and access obligations.
- **Build relationships from embeddings:** rejected; semantic proximity is not a device,
  identity, ownership or transfer relation.
- **Query services live:** rejected; it couples investigations to money-path capacity and
  cannot reproduce a consistent historical snapshot.
- **Make this the first pilot:** rejected; cross-customer disclosure needs proven controls.

## Consequences

**Positive**
- Investigators get explainable, time-bounded relationships and source evidence.
- Fraud and AML share operations while retaining separate purpose policies.

**Negative**
- Device and counterparty data enlarge the sensitive derived-data footprint.
- Cross-customer patterns require strict authorization and side-channel tests.

**Neutral**
- Existing real-time screening and scoring remain authoritative and unchanged.

## Compliance impact

- PCI DSS: tokenize/mask payment identifiers; graph/vector storage admits no PAN or
  sensitive authentication data.
- DORA: test shared-stack capacity/recovery with this workload isolated from payments.
- GDPR: purpose limitation, minimization, retention, audit and human review apply to
  device/behavioral associations; no solely automated adverse decision is introduced.
- PSD2: this lens grants no account access or payment authority.
- CNB: provenance and reproducible snapshots support review; no regulatory approval is claimed.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0308](0308-effective-time-authorization-evidence-graph.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)
