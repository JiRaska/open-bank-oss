---
date: 2026-07-29
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, audit, privacy-gdpr]
summary: "Every credit lifecycle event (transitions, decisions, disclosures, postings, termination) emits a canonical, PII-minimised evidence record into the ADR-0133 tamper-evident chain, reconstructible per loan on one query."
---

# ADR-0214 — Credit lifecycle audit evidence and reconstruction

## Context

"Audit za tím" is not a feature of the credit process — it is the product's licence to
operate. For every loan, a supervisor (ČNB), an ombudsman, or a court must be able to
reconstruct, years later and without trusting the bank's word:

- **who** decided **what** (origination transitions, four-eyes actions, ADR-0211),
- under **which rules** (decision-engine evaluation: inputs, policy version, matched
  rules, outcome, ADR-0213; ML model version where applicable, ADR-0141/0142),
- under **which law** (compliance-pack version, ADR-0212),
- **what the customer was told and when** (disclosure delivery + acknowledgement,
  signature under SCA, cooling-off start),
- and **what happened to the money** (disbursement, installments, payoff, termination,
  write-off — the ADR-0028 ledger trail).

The platform already owns every ingredient: the **tamper-evident audit chain**
(ADR-0133, shipped) in `openbank-audit-service`, the transactional outbox as the single
extraction path (ADR-0003/0050), customer-payment non-repudiation precedent (ADR-0086),
and the GDPR retention model (ADR-0118). What is missing is the **contract**: *which*
credit events must be evidenced, with *which* payload, under *which* correlation, so
that "reconstruct this loan" is a query, not a forensic project. Today the lending
service emits domain events; nothing defines the evidence set a supervisor reads.

## Decision

**D1 — Canonical credit evidence events.** Every item below is a first-class audit
event emitted through the lending service's outbox to the ADR-0133 chain, carrying
`correlationId = loanApplicationId` (later `loanId`), the actor (human principal or
machine actor), and the pack/policy/model versions in force:

| Lifecycle point | Evidence payload (minimum) |
|---|---|
| Application transition (ADR-0211) | from→to, actor, reason, packVersion |
| Policy evaluation (ADR-0213) | table versions, matched rule ids, outcome, reason codes, input-snapshot hash |
| ML decision (ADR-0142, phase 2+) | model id + registry version, score band, adverse-action reasons |
| Disclosure delivery | document id + template version + language, channel, delivered-at, acknowledged-at |
| Signature / SCA | SCA transaction id, signed artefact hash |
| Four-eyes action | maker, checker, decision, reason |
| Disbursement / installment / payoff / write-off | posting reference (ledger idempotency key), amount, kind |
| Termination (ADR-0215) | ground, notice dates, quote version, final settlement reference |

**D2 — Data minimisation in the evidence trail.** Payloads carry identifiers, versions,
hashes and pointers — not bulk PII. The full application data stays in the lending
database (itself access-audited, ADR-0028 D5); the evidence chain proves *what was
decided on which versioned inputs*, via the input-snapshot hash, without becoming a
second PII store. Erasure (ADR-0118) therefore does not require rewriting the chain:
when the source record is erased, the hash still verifies the decision's integrity,
and the payload carries no resurrectable person.

**D3 — The evidence bundle is a reconstruction query, not a report someone assembles.**
`GET /api/v1/loans/{loanId}/evidence` (role-gated `ROLE_AUDIT`/`ROLE_COMPLIANCE`,
itself audit-logged) returns the ordered, chain-verified event sequence for the loan —
application → decision → disclosures → signature → disbursement → servicing →
termination — with the `GET /audit/chain/verify` attestation (ADR-0133). Bundle
generation is read-only and reproducible.

**D4 — Retention follows the stricter of law and pack.** Evidence retention per loan is
the maximum of the platform default (ADR-0118) and the pinned pack's requirement
(ADR-0212) — e.g. the CZ consumer-credit duties — and survives contract end
(withdrawal, settlement, termination) by the statutory period.

## Alternatives considered

- **Rely on service logs + DB history tables.** Status quo trajectory: mutable,
  per-service shaped, and "reconstruct a loan" becomes log stitching — rejected
  precisely by ADR-0101 (DORA Art. 17 gap) and ADR-0133 (tamper-evidence gap).
- **External WORM store now (QLDB/ImmuDB/S3 Object Lock).** Stronger integrity, but a
  hard infrastructure dependency; ADR-0133 already deferred this class. Rejected for
  now; the D1 contract is store-agnostic, so a WORM sink can be added under the same
  events later.
- **Evidence only at decision time.** Tempting minimalism, but supervisors interrogate
  the *whole* life (was the disclosure acknowledged before signature? was the
  termination notice statutory?). Rejected — the table in D1 is the contract.
- **Full payload replication into the audit chain.** Simplest querying, but duplicates
  PII into a second store and collides with ADR-0118 erasure. Rejected — D2's
  hash-and-pointer model.

## Consequences

**Positive**
- "Prove this loan" becomes one role-gated query over a tamper-evident chain — the
  difference between an afternoon and a forensic project at examination time.
- Every credit ADR in this set (0211/0212/0213/0215, and 0142's ML) has exactly one
  place where its evidence duty is specified — no per-feature reinvention.
- Data-minimised payloads keep the chain compatible with GDPR erasure while retaining
  decision integrity.

**Negative**
- The evidence contract is a *tax on every future credit feature*: any new transition,
  decision input, or document type must extend D1 — enforced by review and by tests
  that fail when a transition emits no evidence event.
- Input-snapshot hashing requires canonical serialization; a serialization change is
  an evidence-format change and must be versioned like one.

**Neutral**
- No new infrastructure: outbox + audit-service chain, both shipped.
- Decision-only ADR; the bundle endpoint lands with the first consuming feature.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    Art. 11/17 — tamper-evident, reconstructible record of a money-path ICT
           asset; complements the Temporal execution history (ADR-0211 D2).
- GDPR:    Art. 5(2) accountability + Art. 22 explanation path; Art. 17 erasure
           preserved via D2 minimisation (with ADR-0118).
- PSD2:    SCA evidence on signature (with ADR-0021/0086 precedent).
- CNB:     EBA/GL/2020/06 record-keeping; zákon č. 257/2016 Sb. evidentiary duties;
           AML Act No. 253/2008 §8-style reason trails on human decisions.

## References

- ADR-0133 — tamper-evident audit chain (the integrity substrate)
- ADR-0003 / ADR-0050 — transactional outbox (the single extraction path)
- ADR-0086 — customer payment non-repudiation (precedent: SCA + evidence chain)
- ADR-0211 — origination transitions; ADR-0212 — pack versioning; ADR-0213 — policy
  evaluation records; ADR-0215 — termination evidence
- ADR-0142 / ADR-0141 — ML decision + model-provenance evidence
- ADR-0118 — GDPR data lifecycle and retention
- ADR-0028 D5 — lending access auditing
- DORA Art. 11/17; EBA/GL/2020/06; zákon č. 257/2016 Sb.
