# Compliance

> **Money-path classification:** `openbank-analytics-sink` is **NOT** a money-path service (it is absent from `rules.yaml: money_path_services`). It is a downstream, asynchronous analytics consumer with no role in any payment/settlement path. It does, however, hold a long-lived (10-year) PII-masked, pseudonymous store, so it is firmly in **GDPR + data-governance** scope, and it implements the analytics regulatory hardening of [ADR 0023](../../../../docs/adr/0023-analytics-regulatory-hardening.md) (CNB/EBA/DORA/GDPR/BCBS 239 findings).

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **GDPR** | 10-year PII-masked pseudonymous warehouse; right to erasure | `PayloadMasker` (mask at sink, Art. 25); `ErasureService` + crypto-shred (Art. 17); residency guard (Art. 44) |
| **DORA** (Reg. (EU) 2022/2554) | ICT resilience, monitoring, RPO | readiness probe on ingest freshness/DLQ, metrics/alerting, reconciliation evidence, runbooks |
| **AMLD** | Analytics retains AML-relevant records under statutory hold | `RetentionPolicies` refuse erasure for AML-held categories (Art. 17(3)(b)); 10-year bronze floor |
| **PSD2** | No direct PSD2/Open-Banking surface | analytics is internal-only; no TPP access |
| **NIS2** | Network & info security | mTLS in-cluster, security headers (CSP/HSTS/X-Frame-Options), role-gated REST, JSON audit logs |
| **BCBS 239** | Risk-data aggregation: accuracy, completeness, timeliness | reconciliation vs source-of-record, dead-letter quarantine (no silent gaps), point-in-time `silver_as_of` |
| **CNB/EBA findings** | The 9 regulatory findings on the analytics layer | addressed feature-by-feature in ADR-0023 (F1–F9, below) |

## ADR-0023 regulatory hardening (F1–F9)

| Feature | Topic | Implementation in this service |
|---|---|---|
| F1/F2 | Tamper-evidence | `record_hash` per bronze row + Merkle `integrity_anchors`, chained; authoritative copy in WORM (`S3WormArchive`, Object Lock COMPLIANCE) |
| F3 | Four-eyes recovery loads | `SensitiveReloadService` + `Proposal` state machine; self-approval ⇒ 409; `reload_proposals` trail |
| F6 | GDPR erasure | `ErasureService` + `VaultCryptoErasure` (crypto-shred) / refuse under hold |
| F7 | Schema governance | `SchemaGovernance` quarantines unknown/newer schema to DLQ when `strict` |
| F8 | Ingest freshness / RPO | `IngestFreshness` + `IngestHealthCheck` readiness on lag & DLQ |
| F9 | Data residency | `DataResidencyValidator` startup guard (GDPR Art. 44) |
| — | Completeness | dead-letter quarantine + reconciliation against source of record |

## GDPR mapping

### Lawful basis (Art. 6)
- **Legitimate interest / legal obligation** — internal analytics, regulatory reporting and risk-data aggregation derived from events the bank already lawfully processes. No new collection occurs here.

### Data minimisation & by-design (Art. 5, Art. 25)
Directly-identifying PII is masked at the ingestion boundary (`PayloadMasker`) before any durable write; only a **pseudonymous `aggregateId`** is retained. The warehouse is a derived, masked store — not a second copy of raw customer data.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | Served from the operational source services, not from analytics (analytics holds only masked pseudonyms). |
| Erasure (Art. 17) | `POST /api/v1/analytics/erasure` → crypto-shred if erasable; **refused** with documented legal basis under AML/accounting statutory hold (Art. 17(3)(b)). |
| Restriction (Art. 18) | Achieved upstream; analytics is read-derived. |
| Portability (Art. 20) | N/A — analytics is not the system of record for personal data. |

### International transfers (Art. 44)
`DataResidencyValidator` aborts boot if the warehouse region is not on the allow-list (default `eu-north-1`, ADR-0175 §1). No personal data leaves the approved EU regions.

### Retention (Art. 5(1)(e))

| Data | Retention |
|---|---|
| `bronze_events` (log of record) | 10 years (floor; AMLD/accounting). |
| `backfill_audit`, `reload_proposals` | 10 years (evidence). |
| `integrity_anchors` | indefinite (tamper-evidence must outlive every record). |
| `dead_letter_events` | 1 year (operational, not the log of record). |

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 9 | Identification | `BuildInfo` (gitCommit/buildTime/version) via `/api/v1/info` (openbank-libs). |
| Art. 10 | Detection | readiness probe on ingest lag/DLQ; Prometheus metrics + alerting. |
| Art. 11 | Response & recovery | four-eyes backfill runbook; reconciliation evidence; RPO tied to `max-lag-seconds`. |
| Art. 16/17 | Incident management & reporting | dead-letter visibility + audit/evidence tables. |
| Art. 28 | Third-party risk | all dependencies self-hosted (ClickHouse, Kafka, Vault, Apicurio); S3 Object Lock is the only managed primitive, S3-standard (cloud-agnostic, ADR-0027). |

## Data flows out

- → **BI tools (Metabase / Superset):** read masked gold/silver in ClickHouse only — no operational DB access, no raw PII.
- → **WORM / S3 Object Lock (`eu-north-1`):** integrity anchors (hashes only, no PII).
- → **Vault (optional):** crypto-erasure key operations — no PII payload.

No raw PII is stored or transmitted; no data leaves the approved EU region.

## Security controls

- ✅ AuthN: Keycloak OIDC (RS256 bearer).
- ✅ AuthZ: `@RolesAllowed` on every verb (`ROLE_ADMIN`/`ROLE_AUDITOR`/`ROLE_COMPLIANCE`); no `@PermitAll` mutations.
- ✅ PII masking at the ingestion boundary (irreversible).
- ✅ Separation of duties: four-eyes maker-checker on recovery loads.
- ✅ Tamper-evidence: per-row hash + Merkle anchors in WORM.
- ✅ Data residency: startup guard.
- ✅ HTTP hardening: CSP, HSTS, X-Frame-Options DENY, nosniff, restrictive Permissions-Policy; CORS limited.
- ✅ No outbound load on operational databases (event-fed, no CDC).
- ⚠️ Adapter bindings (ClickHouse/Vault/S3/Apicurio) default to offline no-op in dev; the durable + WORM + Vault path must be explicitly enabled and verified per environment.
