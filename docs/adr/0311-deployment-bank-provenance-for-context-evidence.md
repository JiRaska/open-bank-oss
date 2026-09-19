---
date: 2026-09-18
decision-status: accepted
delivery-status: partial
followup: "#10234 — verify deployment-bank configuration parity before enabling the Lending graph writer"
authors: [Jiri Raska]
supersedes: [0152]
superseded-by: []
delivery-repos: []
tags: [architecture, security, lending, documents]
summary: "Keep one regulated bank per deployment while allowing a fixed deployment-bank provenance label on derived graph and sealed evidence, never a caller-selected tenant partition in source business data."
---

# ADR-0311 — Deployment bank provenance for Context evidence

## Context

ADR-0152 correctly established one regulated bank per deployment, but its blanket
ban on any bank dimension in a schema conflicts with Context's replay namespace
(ADR-0303) and Document's immutable bank provenance for signed evidence. These
labels protect cross-environment import and proof checks; they do not make a
single deployment serve multiple banks. The first Lending graph schema extended
this label into source facts and composite keys, although the loan and collateral
book have no such dimension. That partial model would have implied that one
Lending database could hold several banks without actually isolating the book.

## Decision

One deployment, its service databases, Kafka boundary and credentials still serve
exactly one regulated bank. A second bank requires another deployment. Lending,
loan and collateral source facts use deployment-local identities and foreign
keys; no request or JWT tenant claim can choose a bank partition in those tables.

A derived Context projection may persist the deployment bank identifier to
partition replay generations and reject records from another environment.
Document may persist the same **server-assigned, immutable** identifier with
newly sealed evidence so a purpose-limited proof can detect a cross-environment
mismatch. These fields are provenance, not authorization grants or support for
multiple banks in one deployment. Legacy evidence with no authoritative label
remains unknown and fails closed when bank provenance is required.

The identifier is configured by the operator for each deployment and must not be
accepted from user requests or domain-event payloads as authority. Before a
Lending graph writer is enabled, its trusted deployment identifier must match
Document and Context configuration. A mismatch or unavailable proof stops
publication; a Context label by itself never proves that a loan, document or
party relationship is valid. The source owner must independently check current
facts, purpose and permissions. The graph remains a derived read model and
cannot drive credit calculations.

Any proposal to put multiple regulated banks in one deployment still requires a
new ADR covering every source database, OPA policy, Kafka ACL, ledger boundary,
retention and data residency together. This ADR does not authorize that change.

## Alternatives considered

- **Keep the blanket ban on every bank-labelled column:** rejected because it
  cannot explain the existing Context replay namespace or prove that a signed
  Document came from the same deployment.
- **Add `bank_scope` to every source table:** rejected because it implies
  multi-bank storage without corresponding loan-book, ledger or policy isolation.
- **Trust a bank label supplied with each request or event:** rejected because
  an untrusted producer could turn a label into false evidence or disclosure.

## Consequences

- Lending V18 uses local foreign keys and source-identity uniqueness. Its
  previously proposed per-row `bank_scope` is removed before adoption.
- Context and Document retain deployment provenance. Operations must align their
  configured identifiers and validate that alignment before enabling a publisher.
- An existing single-bank deployment does not need an invented tenant backfill
  for its loan book. Old Document rows remain unknown for proof purposes.
- Cross-borrower graph expansion still needs case/portfolio authorization; a
  shared bank label does not grant visibility.

## Compliance impact

- GDPR: one controller per deployment remains the data ownership boundary; a
  provenance label is not permission to disclose data across cases.
- DORA: replay from another environment must be detected before publication.
- CNB: source records remain within one regulated entity's deployment.
- PCI DSS and PSD2: no new cardholder-data or payment-access capability.

## References

- [ADR-0152](0152-single-tenancy-boundary-statement.md)
- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0307](0307-lending-exposure-guarantor-and-collateral-graph.md)
