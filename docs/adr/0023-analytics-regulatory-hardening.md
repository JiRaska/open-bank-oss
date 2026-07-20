---
date: 2026-05-29
decision-status: accepted
delivery-status: partial
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [analytics, compliance, regulatory-reporting, audit]
summary: "Nine CNB/EBA/DORA/GDPR/BCBS-239 findings against the analytics layer are closed with unit-tested controls in openbank-libs (integrity hashing, Merkle tamper-evidence, maker-checker, crypto-erasure) wired in through ports."
---

# 23. Analytics layer: closing the 9 regulatory findings (CNB/EBA/DORA/GDPR/BCBS 239)

**Delivery note (updated 2026-06-30):**
- **Five in-code controls (F1/F2/F3/F6/F7)** — ✅ Shipped: integrity hash (F1), Merkle-root tamper-evidence (F2), maker-checker (F3), crypto-erasure (F6), schema governance (F7) are build-time gated and tested.
- **External adapter controls** — 🟡 Partial (the 2026-06-30 "not yet implemented" line was false and mis-numbered these as F4/F5/F8/F9): the three named adapters are implemented, tested and build-gated — `VaultCryptoErasure` (F6) and `ApicurioSchemaCatalogSource` (F7) are **deployed** in dev compose; `S3WormArchive` (F2) is code-complete + unit-tested (SigV4 / Object-Lock COMPLIANCE) but **not yet deployed** (awaits a provisioned S3 Object-Lock bucket), assurance 🟡. The genuine remaining gap is **OLTP source-side reconciliation (F4/F5)**: `HttpReconciliationSource` exists (ADR-0026) but is not deployed, so the drift check runs warehouse-only. **F8/F9** are config-only GREEN (no external adapter — never "pending").

## Context

ADR-0022 established the event-fed ClickHouse analytics layer (bronze/silver/gold over the outbox
stream, 10-year bronze retention, recovery flows). A subsequent critical self-review — asking "is
there a hole a CNB/EBA examiner could write up; is it scalable; is the technology right" — surfaced
**nine findings**. None invalidated the architecture, but each was a gap an examiner could legitimately
flag. This ADR records how each is closed and to what assurance level.

The remediation follows the project's established pattern (ADR-0045): **all decision logic is pure,
unit-tested code in `openbank-libs`**, wired into the sink through **ports** whose default bindings are
offline-buildable no-ops; the external integrations (S3 Object Lock, KMS, Apicurio, OLTP/warehouse
readers) land later as `@Alternative @Priority` adapters. So the *control* is real and tested today;
only the *physical integration* is a documented follow-up.

Status legend: 🟢 GREEN = control implemented and tested in-process; 🟡 YELLOW = control logic done +
tested, one external integration still a no-op adapter (operational, not design, work remains).

## The 9 findings and their controls

### F1 — Bronze was claimed as "record of truth" but is operator-mutable → 🟡
A `ReplacingMergeTree` row can be rewritten by anyone with table access, so bronze alone is not a
legal record (BCBS 239 §3 integrity). **Control:** `AnalyticsIntegrity.recordHash` (deterministic
SHA-256 over identity + business content, *excluding* load lineage/time so a correction re-hashes
identically). Stored as `bronze_events.record_hash`. Bronze is reframed as a *derived, rebuildable
projection*; tamper-evidence lives in F2's anchors. *Follow-up:* none for the hash itself (GREEN);
the durable seal is F2.

### F2 — No tamper-evidence / WORM → 🟡
**Control:** `AnalyticsIntegrity.merkleRoot` commits an entire ingest batch to one root; only the root
(+ previous root) is sealed via the `WormArchive` port (`integrity_anchors` table), chaining anchors
into a tamper-evident timeline. Merkle-per-batch (leaves sorted) is parallel- and replay-friendly,
unlike a strict global hash-chain. Logic + chaining tested. *Follow-up:* `LoggingWormArchive` (default)
logs anchors; the S3-Object-Lock-compliance-mode adapter is the documented `@Alternative` → YELLOW.

### F3 — A single operator could reload/restate the 10-year store → 🟢
**Control:** `Proposal<T>` maker-checker state machine — `PROPOSED → APPROVED/REJECTED → EXECUTED`,
with `approve()` hard-rejecting a checker equal to the proposer (`MakerCheckerViolation` → HTTP 409).
`BackfillResource` is reworked into propose/approve/execute verbs; `SensitiveReloadService` +
`ProposalStore` persist the trail. Four-eyes is enforced in code, tested end-to-end. *Follow-up:* only
durable-store swap (`InMemoryProposalStore` → audit-backed); the SoD rule is fully real → GREEN.

### F4 — Reconciliation only compared max-version; evidence not tamper-proof → 🟡
**Control:** `Reconciliation.countDiff` adds an independent per-type row-count tie-out (catches
whole-aggregate loss), and `Reconciliation.fingerprint` produces a signable digest of the outcome,
sealed to the WORM `integrity_anchors` as `RECONCILIATION:*` evidence. Logic tested. *Follow-up:* the
OLTP/warehouse count readers are no-op ports → YELLOW.

### F5 — No proof an event wasn't lost mid-sequence → 🟡
**Control:** `Completeness.gaps` / `gapsFromVersions` detect any version missing in an aggregate's
monotonic sequence — a provably lost event that current-state reconciliation can't see. Surfaced in
the reconciliation result (`completenessGaps`). Logic tested. *Follow-up:* the warehouse
`groupArray(version)` reader is a no-op port → YELLOW.

### F6 — Flat 10-year retention violated GDPR storage-limitation; no erasure path → 🟡
**Control:** `RetentionPolicies` maps each `DataCategory` to its `LegalBasis`, retention `Period`, and
`erasable` flag. AML/accounting/KYC sit under an Art. 17(3)(b) statutory hold (not erasable);
consent/behavioural/operational *are* erasable. `ErasureService` + `ErasureResource` (gated
`ROLE_COMPLIANCE`) apply the policy: refuse-with-legal-basis under a hold, else crypto-shred via the
`CryptoErasure` port. Decision logic tested. *Follow-up:* `NoOpCryptoErasure` → KMS key-destruction
adapter → YELLOW.

### F7 — Unknown/evolved schemas silently written into the 10-year log → 🟡
**Control:** `SchemaCatalog` (`isKnown`/`isCompatible`, backward-compatibility rule) + `SchemaGovernance`
gate in the consumer: a newer-than-known or unknown schema is quarantined to the DLQ (when strict)
instead of corrupting bronze. Gate is opt-in via `openbank.analytics.schema.known` (open by default for
backwards-compatibility). Logic tested. *Follow-up:* durable registry (Apicurio) → YELLOW.

### F8 — Eventual consistency had no observable freshness/RPO signal → 🟢
**Control:** `IngestFreshness` exposes Micrometer gauges (`ingest_lag_seconds`, `last_ingest_epoch_ms`,
`dead_letter_total`); `IngestHealthCheck` (`@Readiness`) goes DOWN when lag exceeds the configured RPO
or the DLQ crosses its threshold. Config-driven, no external dependency → GREEN.

### F9 — No data-residency guard on a PII-bearing store → 🟢
**Control:** `DataResidencyValidator` fails fast at startup if the configured ClickHouse region is not
on the allow-list (GDPR Art. 44 / DORA), turning a mis-deployment into a deploy-time error.
Config-driven, no external dependency → GREEN.

## Decision

Adopt all nine controls as above. The pure primitives live in
`com.openbank.libs.analytics` (`AnalyticsIntegrity`, `MakerChecker`/`Proposal`, `Completeness`,
`RetentionPolicy`/`RetentionPolicies`, `SchemaCatalog`, and the extended `Reconciliation`); the sink
wires them through ports with offline-buildable defaults. Bronze is explicitly **a derived projection**,
not the legal record — the WORM-anchored integrity chain is what an examiner is shown.

## Outcome

| # | Finding | Status |
|---|---------|--------|
| F1 | Bronze record-hash tamper-evidence | 🟡 (hash GREEN; seal = F2) |
| F2 | Merkle anchors → WORM | 🟡 |
| F3 | Maker-checker four-eyes on reloads | 🟢 |
| F4 | Count tie-out + signed evidence | 🟡 |
| F5 | Completeness gap detection | 🟡 |
| F6 | Per-category retention + erasure | 🟡 |
| F7 | Schema governance → DLQ | 🟡 |
| F8 | Freshness/lag metrics + readiness | 🟢 |
| F9 | Data-residency startup guard | 🟢 |

**No finding remains RED.** The four GREEN controls need no external system. The five YELLOW controls
are GREEN in logic and tested; each has exactly one documented `@Alternative` adapter (S3 Object Lock,
KMS, Apicurio, OLTP/warehouse readers) before it is fully GREEN — operational follow-ups, not design
gaps, identical to the `LoggingAuditEventPublisher`/`LoggingAnalyticsSink` precedent.

## Realization progress (2026-05-29)

The first batch of `@Alternative @Priority(100)` adapters has landed, all activated at **build time**
by `openbank.analytics.sink.type=clickhouse` (default unset → the offline `@Default` logging/in-memory
bindings stay, so the service still boots with zero infra). They share one `ClickHouseClient` over the
ClickHouse **HTTP interface via the JDK `HttpClient`** — deliberately **no new Maven dependency**, so
the module stays offline-buildable; SQL building and result parsing are pure and unit-tested (no server).

- **`ClickHouseAnalyticsSink`** (F1) — writes `bronze_events` with the `record_hash` digest; idempotent
  via `ReplacingMergeTree` + pre-insert dedupe. The durable bronze store the layer depends on.
- **`ClickHouseWarehouseStateReader`** (F4/F5) — real `max(version)`, `count(DISTINCT aggregate_id)`
  and `groupUniqArray(version)` reads, so the reconciliation count tie-out and completeness gap check
  run against live warehouse data. *Warehouse side now real; the OLTP source-side reader is still no-op.*
- **`ClickHouseProposalStore`** (F3) — the maker-checker trail is now durable (`reload_proposals`,
  `ReplacingMergeTree(updated_at)`, read with `FINAL`), surviving restart. **F3 is now fully GREEN** —
  both the SoD logic and the durable trail are real.
- **`ClickHouseWormArchive`** (F2) — a *queryable mirror* of `integrity_anchors` (append + chain via
  `latest()`). Honestly logged at WARN as NOT the authoritative immutable seal — that remains the
  S3-Object-Lock follow-up. So F2 stays YELLOW.

The second batch wires the two infra-backed controls that `openbank-infra` already provisions
(`vault:8200`, `schema-registry:8081`), again over the JDK `HttpClient` with **no new Maven
dependency** — request building and response parsing are pure and unit-tested, the HTTP I/O sits
behind an `open` overridable seam so tests need no server:

- **`VaultCryptoErasure`** (F6) — crypto-shreds via Vault **Transit** key destruction. Gated by
  `openbank.analytics.erasure.backend=vault` over the `@Default` `NoOpCryptoErasure`. Two-step
  `POST {mount}/keys/{name}/config {"deletion_allowed":true}` → `DELETE {mount}/keys/{name}`; a 404 on
  the first step is an idempotent no-op (key already gone → returns 0, skips the destroy). Erasure
  destroys the per-subject key so ciphertext is unreadable, **without mutating the immutable bronze
  log** (GDPR Art. 17 honoured against an append-only store). **F6 now reaches real key destruction.**
- **`ApicurioSchemaCatalogSource`** (F7) — loads the governance catalogue from the Apicurio Registry
  v2 REST API (`/groups/{group}/artifacts` → `.../{id}/versions`; artifact id = `eventType`, registered
  versions = accepted `schemaVersion`s). Gated by `openbank.analytics.schema.backend=apicurio` over the
  `@Default` `ConfigSchemaCatalogSource` (static `schema.known` spec). `SchemaGovernance` now depends on
  the injected `SchemaCatalogSource` port rather than parsing config directly. **Boot-resilient:** a
  registry unreachable at startup returns an empty catalogue (gate open) and logs loudly rather than
  failing the boot — mirroring the "open by default" config source. **F7 now reads a durable registry.**

The third increment makes **F2** real with the authoritative seal:

- **`S3WormArchive`** (F2) — seals each `IntegrityAnchor` into an S3 bucket with **Object Lock in
  COMPLIANCE mode** (`x-amz-object-lock-mode` + `x-amz-object-lock-retain-until-date`), so a sealed
  object cannot be overwritten or deleted by anyone — not even account root — until retention (>= the
  10y bronze minimum) expires. That is the genuine WORM guarantee the operator-mutable ClickHouse
  mirror cannot give. Keys embed an inverted timestamp so `latest()` is a cheap one-key list. Gated by
  `openbank.analytics.worm.backend=s3` at `@Priority(200)` (above the mirror, so it wins when both are
  set in prod). Signed with **AWS Signature V4 over the JDK `HttpClient`, no AWS SDK** — the
  canonicalisation/signing is pure and unit-tested against the published AWS SigV4 `get-vanilla` vector
  (canonical-request hash `bb579772…e63`), the seal/read flow behind an overridable seam.

  **Technology note (MinIO).** The obvious local Object-Lock target used to be MinIO, but its community
  edition was put into maintenance mode (Dec 2025) and the repository was archived as *no longer
  maintained* (2026) — so it is deliberately **not** adopted. The adapter targets the **S3 API
  standard**, not a product: production seals against AWS S3 (which enforces COMPLIANCE at the service
  level). Lightweight local emulators were rejected too — SeaweedFS, though actively maintained,
  **does not enforce** Object-Lock retention as of 2026 (objects remain deletable), which would make
  the seal theatre. So there is no faithful local WORM container; dev keeps the ClickHouse mirror (this
  gate unset) and the real seal is exercised against S3 in the deployed environment.

**Runtime wiring.** Dev infra (`openbank-infra/docker-compose.yml`) now provisions a `clickhouse`
service (with the source-controlled warehouse DDL auto-applied on first init) and the `analytics-sink`
service itself, with all three gates set (`ANALYTICS_SINK_TYPE=clickhouse`,
`ANALYTICS_ERASURE_BACKEND=vault`, `ANALYTICS_SCHEMA_BACKEND=apicurio`). So the adapters above are no
longer only unit-tested — they execute end-to-end against ClickHouse, Vault Transit and Apicurio in
the local stack. The offline `@Default` bindings still apply whenever the gates are unset (CI, tests).

**Remaining follow-up:** the OLTP **source-side** reconciliation reader (F4/F5 source side). The
`ReconciliationSource` port is still the no-op default because a real implementation must read each
domain service's `GROUP BY aggregate max(version)` — cross-service endpoints / read access that do not
exist yet. The warehouse side is real, so the drift check runs one-sided until the source reader lands.
F2's adapter now exists (`S3WormArchive`); it needs a provisioned S3 Object-Lock bucket (cloud) to be
active in an environment — the code is no longer the gap.

## Consequences

**Positive.** Every examiner-visible gap now has a named, tested control and a clear assurance level;
the segregation-of-duties, retention legality, freshness observability and residency guards are real
today; the tamper-evidence design (Merkle-per-batch anchored to WORM) is cheap and scalable.

**Negative / trade-offs.** One control still lacks a real implementation: the live OLTP source-side
reconciliation read (F4/F5 source side) needs cross-service access that does not exist yet, so the
drift check runs one-sided. The integrity model assumes the WORM target is genuinely immutable — a
mis-configured (Object-Lock-disabled) S3 bucket would silently weaken F1/F2/F4, so bucket provisioning
must be verified, not assumed. All the real adapters (F1/F2/F3/F6/F7) are build-time gated, so a
deployment that forgets a gate silently keeps the no-op default — the gates must be part of the
production profile.

## References
- ADR-0022 — the analytics layer these findings harden
- ADR-0003 — outbox/Kafka (the single extraction path, durable backfill source)
- ADR-0045 — lightweight-over-cluster operability (the ports + no-op-default pattern)
- BCBS 239 §3 (accuracy/completeness/integrity); GDPR Art. 5(1)(e), 17, 17(3)(b), 44;
  DORA (ICT monitoring, RPO); EBA/GL outsourcing & SoD
- `openbank-libs` — `analytics.AnalyticsIntegrity`, `analytics.MakerChecker`, `analytics.Completeness`,
  `analytics.RetentionPolicy`, `analytics.SchemaCatalog`, `analytics.Reconciliation`
- `openbank-analytics-sink` — `WormArchive`/`LoggingWormArchive`, `ProposalStore`/`SensitiveReloadService`,
  `ErasureService`, `SchemaGovernance`, `IngestFreshness`, `IngestHealthCheck`, `DataResidencyValidator`,
  `clickhouse/V1__analytics_bronze_silver.sql` (integrity_anchors, reload_proposals, record_hash)
- `openbank-analytics-sink` adapters — `infrastructure/sink/ClickHouseAnalyticsSink` (F1),
  `infrastructure/clickhouse/*` + `reconcile`/`worm`/`proposal` ClickHouse readers (F2/F3/F4/F5),
  `infrastructure/erasure/VaultCryptoErasure` (F6), `infrastructure/schema/ApicurioSchemaCatalogSource`
  + `SchemaCatalogSource` port (F7) — all `@Alternative @Priority(100)`, build-time gated, JDK-HttpClient
