# ADR-0133: Tamper-evident audit chain

Date: 2026-06-29
Decision-Status: Accepted
Delivery-Status: Shipped
Author(s): Jiri Raska

## Context

Banking regulators (GDPR Art. 32, BCBS 239, ČNB AML Decree) require that an audit log cannot be
modified after the fact without detection. A plain relational table allows `UPDATE` and `DELETE` by
any sufficiently privileged database user, including a compromised service account. OpenBank's
`openbank-audit-service` collects audit events from every service via Kafka. Without a tamper-evidence
layer, the log satisfies availability but not integrity — an attacker or insider could rewrite history.

## Decision

We will maintain a **SHA-256 hash chain** inside `openbank-audit-service`. Every `AuditEntry` row
stores two additional columns:

- `prev_hash` — the `record_hash` of the immediately preceding entry (or a fixed genesis constant
  for the first row).
- `record_hash` — SHA-256 over `(prev_hash ‖ id ‖ eventType ‖ aggregateType ‖ aggregateId ‖ actorId
  ‖ payload ‖ sourceService ‖ correlationId ‖ occurredAt)`.

Appending a new entry and computing its hash is serialised through a per-JVM `Mutex` (upgraded to a
DB-level advisory lock once replicas > 1). A `GET /audit/chain/verify` endpoint walks the chain
oldest-first and reports the first broken link, the count of pre-chain rows (written before V5 of the
schema), and the overall `intact` boolean. This endpoint is exposed only on the management port
(`/q/…`) and protected by OPA admin role.

The chain detects any in-place `UPDATE`, `DELETE`, or row reordering. It does **not** prevent a
sufficiently privileged DB admin from truncating and rebuilding the table — that threat model requires
an external WORM store, which is deferred to a future ADR (see Consequences).

## Alternatives considered

- **External WORM store (AWS QLDB / Immudb)** — strong integrity, no internal-admin threat. Rejected
  for now: adds a hard infrastructure dependency, raises cost, and the current threat model (detect
  accidental or opportunistic tampering) is satisfied by the hash chain. Revisit when a ČNB audit
  requires external evidence.
- **DB-native append-only table (row-level security + revoke DELETE)** — achievable in PG 18 with
  RLS. Simpler than a hash chain but does not produce a verifiable proof that survives a DB restore.
  Can be layered on top of the hash chain in the future.
- **Write directly to S3 with Object Lock** — durable and cheap. Does not support live queries. Useful
  as a secondary archive; not a replacement for the hot path.

## Consequences

**Positive**
- Any in-place edit, delete, or row reorder breaks the chain at the first affected row and is detected
  by `verifyChain()`.
- Satisfies GDPR Art. 32 (integrity), BCBS 239 (audit trail auditability), and ČNB AML Decree
  Section 9 (record integrity).
- No additional infrastructure component in the hot path — chain computed in the application layer.

**Negative**
- A DB admin who truncates the table and rebuilds it can forge the chain. External WORM store is the
  mitigation; deferred.
- Pre-chain rows (written before Flyway V5) are unverifiable retroactively and are reported as
  `unchained` in the verification result.
- The per-JVM Mutex is a concurrency bottleneck at high ingestion rates; must be replaced with a
  DB-level advisory lock before horizontal scaling of `audit-service`.

**Neutral**
- `verifyChain()` is O(n) over all rows. For long-lived deployments, anchor-based incremental
  verification will be needed (noted as a TODO in the implementation).

## Compliance impact

- GDPR: Art. 32 (integrity and confidentiality of processing)
- DORA: Art. 9(4)(d) (data integrity for ICT risk management)
- ČNB: AML Decree Section 9 (record-keeping integrity)
- PCI DSS: Req. 10.3 (audit log protection)
- PSD2: not directly applicable

## References

- `openbank-audit-service/.../AuditRepository.kt` — `save()`, `verifyChain()`, `chainHash()`
- `openbank-audit-service/.../AuditResource.kt` — `GET /audit/chain/verify`
- ADR-0023 (analytics regulatory hardening) — motivated the chain design
- ADR-0086 (payment non-repudiation) — complementary per-payment chain
