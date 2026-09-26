---
date: 2026-09-26
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [audit, libs, compliance, kafka]
summary: "Producers hash-link their own audit events (producer, seq, prevHash, SHA-256 over canonical JSON) and deliver them via a transactional outbox, extending ADR-0133's chain from the audit store back to the emitting service."
followup: "#10908 — phase 1 ships the libs publisher; no service is migrated yet"
---

# ADR-0317 — Producer-side hash-linked audit envelope via outbox

## Context

ADR-0133 gives `openbank-audit-service` a SHA-256 chain over the rows it stores. That chain begins
at ingest: it proves the store was not edited after the fact, and nothing about the path before
it. The shared `AuditEventPublisher` in `openbank-libs-runtime` has one implementation, a logging
fallback that writes one line per event. An event lost before the audit topic, sent twice,
reordered, or altered in transit reaches the store as a well-formed row, and the store's own chain
then attests to it.

ADR-0226 already fixed the envelope's identity dimensions (`channel`, `actChain`, `sessionId`) as
additive, nullable fields that producers adopt channel by channel. The audit-service consumer reads
the payload best-effort (`eventId`, `eventType`, `aggregateType`, `aggregateId`, `actorId`,
`actorType`, `sourceService`, `correlationId`, `occurredAt`, `channel`, `actChain`, `sessionId`),
stores the raw payload verbatim, and ignores keys it does not know. That tolerance is what allows
integrity fields to be added without a coordinated release.

## Decision

We will make the producer the first link of the chain.

1. **Envelope.** Every audit event emitted through the hash-linked publisher carries four additive
   fields: `producer` (the emitting service, `quarkus.application.name`), `seq` (a per-producer
   counter starting at 1, strictly incrementing by 1), `prevHash` (the previous event's `hash`, or
   64 zeros for `seq = 1`) and `hash`.
2. **Hash.** `hash` is lower-case hex SHA-256 over the canonical JSON of the whole envelope minus
   the `hash` key. Canonical JSON means: object keys sorted by code point, no insignificant
   whitespace, strings JSON-escaped, instants as ISO-8601 UTC strings, nulls kept. The canonical
   form is also the wire payload, so a verifier recomputes it from exactly the bytes it received.
3. **Delivery.** The envelope is written to a transactional outbox in the producer's database and
   relayed by the existing outbox dispatcher (ADR-0050). The outbox aggregate id is a
   name-based UUID of the producer, so all of one producer's events share a Kafka partition and
   arrive in `seq` order.
4. **Serialisation.** Reading the chain head and inserting the next row happen in one transaction
   that holds a per-producer lock. The libs publisher adds an in-JVM mutex; the service's
   `AuditChainOutbox` implementation owns the cross-replica lock.
5. **Opt-in.** The publisher is selected by the build property
   `openbank.audit.publisher=hash-linked-outbox`. Unset, the logging publisher stays the default
   and no service changes behaviour.
6. **Compatibility.** Every key the audit-service consumer reads keeps its meaning. The new fields
   are additive; the consumer stores them inside the verbatim payload today.

Phase 1 (this ADR's first delivery) ships the libs pieces only. Migrating services, and having
audit-service verify each producer's chain on ingest (gap and fork detection), are follow-ups.

## Alternatives considered

- **Keep the chain only in audit-service (status quo).** No producer change. Rejected: it cannot
  see loss or alteration before ingest, which is the gap this ADR exists to close.
- **Publish directly to Kafka from the request path.** Simpler than an outbox. Rejected: the audit
  event and the business change would not commit atomically, so a crash between them loses the
  event or records one for a change that rolled back — the dual-write the outbox pattern exists for.
- **Sign events (HMAC or asymmetric) instead of hash-linking.** Proves origin, not completeness: a
  dropped event leaves no trace. Signing can be layered on the hash later without changing the
  envelope's shape.

## Consequences

**Positive**
- A missing, duplicated or reordered event shows up as a `seq` gap or a `prevHash` mismatch, and an
  altered one as a `hash` mismatch, independently of the audit store.
- Audit delivery becomes as durable as the business transaction that caused it.

**Negative**
- Every migrating service needs an outbox table, a lock-holding `AuditChainOutbox` implementation
  and a Kafka channel with ACLs.
- The per-producer lock serialises audit writes within one service.
- A producer that loses its outbox table restarts at `seq = 1`; the verifier must treat that as a
  reported break, not silently accept it.

**Neutral**
- The canonical JSON form is a contract; changing it later requires a version marker.

## Compliance impact

- PCI DSS: not applicable — no cardholder data handling changes.
- DORA: strengthens the integrity of the ICT incident audit trail that ADR-0133 already provides.
- GDPR: supports integrity of processing records; the payload remains subject to PII masking by
  the producer, unchanged.
- PSD2: not applicable — no payment-flow change.
- CNB: supports record-keeping integrity as described in ADR-0133.

## References

- ADR-0133 — Tamper-evident audit chain
- ADR-0226 — Cross-channel audit correlation
- ADR-0050 — Regulatory-grade transactional-outbox dispatch
- #10908
