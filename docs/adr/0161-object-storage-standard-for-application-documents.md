---
date: 2026-07-13
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [documents, infrastructure, libs]
summary: "Standardize binary-artifact storage on a single framework-free ObjectStorePort in the domain libs, with S3 and Postgres adapters selected by one config key and a reusable WORM-by-default documents bucket in Terraform."
---

# ADR-0161 — Object-storage standard for application binary artifacts

**Delivery note (updated 2026-07-17):** D1 + D2 shipped, D3 pending.
- **D1 (`ObjectStorePort` in domain libs)** — ✅ Shipped: `openbank-libs-domain/.../storage/ObjectStorePort.kt`
  (framework-free `put`/`get`/`exists`/`presignGet`, service-namespaced keys).
- **D2 (S3 + Postgres adapters behind one config key)** — ✅ Shipped: `S3ObjectStore` + `PostgresBlobStore`
  in `openbank-libs-runtime` (AWS SDK v2 in the version catalog); the Postgres backend is consumed by
  `openbank-document-service`'s `V4__add_object_store_blobs.sql`. Caveat: the S3 adapter applies SSE-AES256
  and defers SSE-KMS + Object-Lock WORM to the D3 bucket.
- **D3 (reusable Terraform documents bucket, WORM by default)** — ⬜ Pending: no `openbank-documents-<env>`
  bucket or reusable module exists; document-service gitops notes the real S3 adapter is "deliberately NOT
  wired here". Until D3 ships, the SSE-KMS / WORM-at-rest guarantee D2 defers to it is unrealized.

## Context

The platform has **no shared abstraction for storing binary artifacts** (documents,
generated PDFs, uploaded evidence). Every place that has needed one so far has
improvised, and the improvisations disagree:

- `openbank-party-service` `V10__party_document_files.sql` stores document bytes as a
  Postgres `BYTEA` column with an explicit in-migration TODO *"Production: replace with S3
  object references."*
- `openbank-dispute-service` `openapi.yaml` carries `fileReference` as *"an opaque
  reference (e.g. an object-storage key or URL). No blob storage is implemented by this
  service."* — i.e. it punts entirely.
- `openbank-analytics-sink` `S3WormArchive.kt` is the only real object-store code: a
  hand-rolled, SDK-less AWS **SigV4** signer over the JDK `HttpClient`, writing
  integrity anchors to an S3 **Object Lock (COMPLIANCE)** bucket. It is build-gated
  (`@IfBuildProperty`), narrow to anchor JSON, and **not packaged as a library**.

Meanwhile the AWS platform already provisions hardened S3 buckets for adjacent purposes
(`audit-baseline` CloudTrail/Config log archive with Object Lock + versioning; CNPG
`db-backups`; the static-site origin). So the *operational* pattern for a compliant,
WORM-capable S3 bucket is well established in Terraform — what is missing is an
**application-layer contract** and a **provisioning module earmarked for documents**.

ADR-0162 (document management) needs exactly this and cannot ship without a decision here.
`MinIO` is deliberately excluded: it was already rejected across the platform (the
community edition was archived upstream in 2026; the analytics-sink code and gitops carry
that note), and there is no MinIO deployment anywhere in `openbank-infra/gitops`.

## Decision

### D1 — A single `ObjectStorePort` in the domain libs

We will define one port in `openbank-libs-domain`
(`com.openbank.libs.storage.ObjectStorePort`), framework-free, as the *only* sanctioned way
a service reads or writes a binary artifact:

```kotlin
interface ObjectStorePort {
    suspend fun put(key: String, bytes: ByteArray, contentType: String, metadata: Map<String, String> = emptyMap())
    suspend fun get(key: String): ByteArray
    suspend fun presignGet(key: String, ttl: Duration): String   // time-boxed download URL
    suspend fun exists(key: String): Boolean
}
```

Keys are opaque, service-namespaced (`<service>/<aggregate>/<uuid>`), and never contain
PII (ADR privacy rule: no personal data in URLs/keys). No bucket names or credentials leak
into the domain; those live in the adapter config.

### D2 — Two adapters, one contract

- **`S3ObjectStore`** (production) in `openbank-libs-runtime` — backed by the AWS SDK v2
  (`software.amazon.awssdk:s3`, Apache-2.0, **added to the version catalog** as part of
  delivering this ADR). It uses **S3 Object Lock in COMPLIANCE mode** for write-once
  artifacts and SSE-KMS encryption at rest. This supersedes the bespoke SigV4 signer in
  analytics-sink as the platform default (analytics-sink may migrate onto it later; not
  required by this ADR).
- **`PostgresBlobStore`** (dev/test/low-volume) — bytes in a `BYTEA` table, the same shape
  party-service V10 already uses. Selected by config so `@QuarkusTest` and local dev need
  no S3/localstack. This is the honest replacement for the party-service TODO: the *port*
  is the contract, the BYTEA table is one valid backing of it.

Adapter selection is a single config key (`openbank.objectstore.backend: s3|postgres`),
defaulting to `postgres` for non-production profiles.

### D3 — Provisioning: one bucket module per data domain, WORM by default

A reusable Terraform module (mirroring `audit-baseline`) provisions a per-domain bucket
with: versioning on, Object Lock (COMPLIANCE) with a default retention matching the
domain's declared `retentionPolicy`, SSE-KMS, public-access-block, and a lifecycle policy.
Documents get `openbank-documents-<env>`. The bucket is **defined in Terraform** — closing
the gap that the `openbank-analytics-worm` bucket exposed (its code exists but no Terraform
provisions it).

## Alternatives considered

- **Keep `BYTEA`-in-Postgres as the only mechanism.** Simple, transactional, no new infra.
  Rejected as the *sole* mechanism: banking documents (statements, signed contracts) grow
  unbounded and are write-once — bloating the OLTP database with multi-MB blobs hurts
  backup/restore, replication lag, and CNPG storage sizing. Kept as a *dev/low-volume*
  adapter behind the port, not as the production answer.
- **Generalize the analytics-sink SigV4 signer into the shared library.** It works and has
  no SDK dependency. Rejected: it implements only PUT/GET/List for a fixed JSON shape,
  has no presigning, multipart, or KMS integration, and re-implementing the S3 surface by
  hand is exactly the maintenance liability the AWS SDK exists to remove. The SDK is
  Apache-2.0, so there is no licensing reason to avoid it.
- **A third-party object-storage abstraction (e.g. Apache jclouds).** Rejected: adds a
  heavy multi-cloud dependency for a single-cloud (AWS) deployment; our single-tenancy
  posture (ADR-0152) means one cloud per deployment, so multi-cloud portability buys
  nothing here.

## Consequences

**Positive**
- One contract, two honest backings — services stop improvising blob storage.
- WORM/Object-Lock + SSE-KMS by default gives evidential integrity and encryption-at-rest
  for free, reusing the proven `audit-baseline` provisioning pattern.
- The party-service V10 TODO and the dispute-service "no blob storage" gap now have a
  named home to migrate onto.

**Negative**
- A new dependency on the AWS SDK in the version catalog, and a new Terraform bucket module
  to maintain. Mitigated by the Postgres adapter keeping local/dev/test S3-free.

**Neutral**
- Object storage is reference/evidence infrastructure, not money-path; an S3 outage
  degrades document retrieval, it does not move (or block) money.

## Compliance impact

- PCI DSS: not applicable (no PAN stored in documents by policy).
- DORA:    ICT third-party (S3) — covered by existing AWS DORA register; WORM supports
  operational-resilience evidence retention.
- GDPR:    Art. 5(1)(e) storage limitation + Art. 32 (encryption at rest, SSE-KMS). The
  port carries no PII in keys; document *content* classification/retention is the owning
  service's `governance.yaml` responsibility (see ADR-0162, ADR-0118).
- PSD2:    not applicable.
- CNB:     supports records-retention obligations via Object Lock COMPLIANCE mode.

## References

- ADR-0162 — Document management, templating & e-signature (the first consumer)
- ADR-0022 / ADR-0023 — analytics WORM archive (the existing SigV4 precedent)
- ADR-0118 — GDPR data lifecycle (retention vs. erasure)
- ADR-0152 — single-tenancy boundary (why single-cloud is fine)
- `openbank-party-service/src/main/resources/db/migration/V10__party_document_files.sql`
- `openbank-analytics-sink/.../infrastructure/worm/S3WormArchive.kt`
