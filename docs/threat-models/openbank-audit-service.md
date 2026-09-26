<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — audit ingestion and hash-chain append

Status: implementation review required. Scope: Kafka audit ingestion, PostgreSQL append and
failure visibility. This model does not certify production deployment or regulatory compliance.

## Assets and trust boundaries

The protected assets are the original event evidence, immutable entry identity, attribution and
ordered hash-chain links. Producers cross a Kafka authorization boundary; the audit consumer
crosses a separate PostgreSQL boundary. Dead-letter delivery retains failed input for recovery.
Neither receipt by Kafka nor an intact chain establishes that every expected producer event exists.

## Threats and controls

- **Loss after a store failure:** acknowledge only after persistence. Parsing or persistence failure
  invokes NACK, and a failed NACK propagates. Configured DLQ serialization preserves the original
  String payload instead of JSON-encoding it again. `AuditDlqIT` exercises malformed input,
  a rejected database insert, continued consumption and producer-ID replay with a real broker.
- **Duplicate evidence after a lost reply:** a producer event ID identifies the entry. Without one,
  the original Kafka topic/partition/offset supplies a deterministic fallback. Body-only replay
  cannot recreate that fallback identity. Existing rows are not backfilled.
- **Forked chain across replicas:** a transaction-scoped database lock covers the duplicate check,
  current head and append. `AuditConcurrentAppendIT` forces two independent writers to overlap;
  hash and timestamp canonicalization remain covered by the existing round-trip tests.
- **Silent failure despite a healthy chain:** `AuditIngestionFailed` observes recent failed ingestion,
  including the first observed positive counter after startup. Its resolution is not replay proof.
- **Disclosure in error handling:** ingestion failures log the exception, not the source payload.
  Operational evidence remains access controlled; it must not be copied into public issues.

## Residual risks and rollout

Producer authenticity and Kafka ACLs remain deployment controls. Local tests do not prove production
retention, replication, authorization or completeness of all producers. Metadata-only operational
replay must preserve the original broker address. Hash chaining detects inconsistent links;
protection against privileged wholesale rewriting depends on trusted external anchoring.
All writers must adopt the database append lock before relying on concurrent safety. Rollback to
acknowledgement-on-failure is unsafe. See `docs/runbooks/audit-ingestion-recovery.md`.

Each append allocates its numeric row ID from the existing database sequence while holding the
append lock. Per-process cached ID blocks are not used. The sequence and existing rows stay
unchanged; gaps between numeric IDs are expected and are not missing-event evidence.

### Policy decision point wiring

`AuthzProducer` registers the shared `OpaSidecarPolicyDecisionPoint` as the service's CDI policy
decision point. `opa.url`, `opa.path` and `opa.timeout-ms` select the sidecar and bound requests.
A running OPA sidecar alone does not provide this bean: without the producer enforced `@Authorize`
requests fail with `POLICY_DECISION_POINT_UNAVAILABLE`, while advisory mode cannot exercise the
policy. Missing/unreachable OPA remains fail-closed when enforcement is enabled. The deployment's
existing enforcement setting and role/action policy are unchanged by this wiring repair.

The real-services settlement proof with `--with-audit` uses enforced authorization and the generated
audit bundle, then checks complete outbox-to-audit payload correspondence and the authenticated
integrity endpoint. It does not attest the deployed enforcement setting or external signed anchors.
