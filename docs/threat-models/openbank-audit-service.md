<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-audit-service

Status: implementation review required. Scope: the authenticated REST read/verify surface and its
authorization wiring. This model does not certify production deployment or regulatory compliance.
`openbank-audit-service` is not listed in `rules.yaml: money_path_services`; this document is kept
voluntarily because the service holds the fleet's evidentiary record.

## Assets and trust boundaries

The protected assets are the persisted audit entries, their hash-chain links and the integrity
verdicts the service reports. Callers reach the REST surface through OIDC authentication and the
`@Authorize` interceptor; each authorization decision crosses the boundary to the per-pod OPA
sidecar.

## Change log

### 2026-10-03 — policy decision point wiring

The service now opts in to the shared libs-runtime `OpaPolicyDecisionPointProducer`
(`openbank.authz.opa-pdp-producer.enabled: true`), which registers `OpaSidecarPolicyDecisionPoint`
as the CDI policy decision point. `opa.url`, `opa.path` and `opa.timeout-ms` select the sidecar
and bound each request. Before this, no `PolicyDecisionPoint` bean existed in the service: a
running OPA sidecar alone did not authorize anything — enforced `@Authorize` requests took the
interceptor's `pdp_unconfigured` branch and advisory mode never consulted the policy.

- **Elevation of privilege:** with the producer present, an OPA deny is enforced even for a caller
  whose role would otherwise look eligible. `AuditAuthzWiringIT` drives real HTTP through CDI and
  the interceptor against a local policy fixture and asserts both an allow (200) and a deny (403).
- **Denial of service:** a missing or unreachable sidecar remains fail-closed when enforcement is
  enabled; the timeout bounds how long a request waits on it.

Residual: the deployed enforcement setting and the role/action policy are unchanged by this
wiring and are not attested by the test, which uses a fixture rather than the generated bundle.

### 2026-10-03 — ingestion durability and serialized chain appends

Scope widens to Kafka audit ingestion, PostgreSQL append and failure visibility. Producers cross a
Kafka authorization boundary; the consumer crosses a separate PostgreSQL boundary. Dead-letter
delivery retains failed input for recovery. Neither receipt by Kafka nor an intact chain
establishes that every expected producer event exists.

- **Loss after a store failure (Repudiation/Tampering):** acknowledge only after persistence.
  Parsing or persistence failure invokes NACK, and a failed NACK propagates. Configured DLQ
  serialization preserves the original String payload instead of JSON-encoding it again.
  `AuditDlqIT` exercises malformed input, a rejected database insert, continued consumption and
  producer-ID replay with a real broker.
- **Duplicate evidence after a lost reply:** a producer event ID identifies the entry. Without one,
  the original Kafka topic/partition/offset supplies a deterministic fallback. Body-only replay
  cannot recreate that fallback identity. Existing rows are not backfilled. A reused context
  commitment ID carrying different evidence is still rejected, now inside the locked append.
- **Forked chain across replicas (Tampering):** a transaction-scoped PostgreSQL advisory lock
  covers the duplicate check, current head and append, replacing the in-process mutex that was
  only correct for a single replica. `AuditConcurrentAppendIT` forces two independent writers to
  overlap; hash and timestamp canonicalization remain covered by the existing round-trip tests.
  Each append allocates its row ID from `audit_entries_seq` while holding the lock; per-process
  cached ID blocks are not used, so gaps between IDs are expected and are not missing-event
  evidence.
- **Silent failure despite a healthy chain:** `AuditIngestionFailed` observes recent failed
  ingestion, including the first observed positive counter after startup. Its resolution is not
  replay proof.
- **Information disclosure in error handling:** ingestion failures log the exception, not the
  source payload.

Residual: producer authenticity and Kafka ACLs remain deployment controls. Local tests do not prove
production retention, replication, authorization or completeness of all producers. All writers
must adopt the append lock before relying on concurrent safety; rollback to
acknowledgement-on-failure is unsafe. See `docs/runbooks/audit-ingestion-recovery.md`.
