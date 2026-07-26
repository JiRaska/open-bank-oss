# Compliance

The audit-service is a **compliance-domain** service (`governance.yaml: dataDomain: compliance`, `dataClassification: restricted`). It is the platform's evidence store — it does not itself sit on the money path, but it underpins the auditability of every service that does. It is **not** in `rules.yaml: money_path_services`.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **DORA** (Reg. (EU) 2022/2554) | ICT incident evidence & operational resilience | immutable event log, security-event flagging, health/metrics, runbooks ([05](./05-operations.md)) |
| **EBA ICT & Security Risk Guidelines** | Audit logging requirements (cited verbatim in V2 migration) | `is_security_event` for SIEM, immutable rows, 10-year `retention_until` |
| **GDPR** (Reg. (EU) 2016/679) | Stores personal data (IP, user-agent, actor, event payloads) | `data_sensitivity` classification, restricted access roles, retention overrides erasure |
| **AMLD / AML** | Long-term retention of transactional evidence | 10-year retention (AMLD 6 Art. 40) enforced by trigger + delete-blocking rules |
| **PSD2** | Records consent and payment-initiation events flowing through the fleet | consumes `openbank.consent.events`, `openbank.transactions.*` |
| **NIS2** | Network & information security, security event recording | `is_security_event`, mTLS in-cluster, strict security headers, role-gated read API |
| **SOX-style internal controls** | Read-only, segregated audit evidence | `ROLE_AUDITOR` segregation, K7 regression guard |

## GDPR mapping

### Lawful basis (Art. 6)

- **Legal obligation** (Art. 6(1)(c)) — primary: keeping an audit trail of banking operations is a regulatory requirement (DORA, EBA ICT, AML, CNB).
- **Legitimate interest** (Art. 6(1)(f)) — secondary: security monitoring and fraud/abuse detection (`is_security_event`, `risk_score`).

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | Trail for a subject's aggregate via `GET /api/v1/audit/entries/{aggregateId}` (role-gated; subject access handled by the responsible controller service, not self-serve) |
| Rectification (Art. 16) | **Not applicable** — audit rows are immutable by design; a correction is an appended compensating entry |
| Erasure (Art. 17) | **Not applicable / overridden** — AML & EBA ICT retention (10 years) is a legal obligation that overrides erasure; the DB `no_delete` rule enforces it physically |
| Restriction (Art. 18) | Access restriction is achieved by role gating, not by altering records |
| Portability (Art. 20) | N/A — audit evidence is not subject-provided contractual data |
| Object (Art. 21) | N/A — no marketing/profiling processing |

### Data flows

**In (consume, Kafka):** account, transaction, balance, party, kyc, consent services → audit-service. The audit store is a downstream **processor** of payloads whose controllers are the originating services; classification follows the most sensitive field present.

**Out:**
- Read API → admin-ui (auditors/admins/compliance), intra-OpenBank, role-gated.
- `audit_outbox` → Kafka re-emit (compliance/SIEM stream) — wired in code, outbound channel not yet configured ([05](./05-operations.md)).

No data leaves the EU/EEA region.

### Retention (Art. 5(1)(e))

| Data | Retention | Mechanism |
|---|---|---|
| Every audit entry | 10 years from `occurred_at` | `trg_audit_retention` trigger + `audit-retention-days: 3650` |
| Security events | 10 years (same store, flagged) | `is_security_event` |

Deletion before expiry is blocked at the database layer (`no_delete_audit` rule). Purge after expiry is a separate, audited maintenance job.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) via `openbank-libs` `/api/v1/info` |
| Art. 10 | Detection | `is_security_event` flag + Prometheus metrics/alerting on ingest lag and error rate |
| Art. 11 | Response & recovery | runbooks in [05](./05-operations.md); Kafka `earliest` replay for backfill (RPO 0 for committed rows) |
| Art. 16 | Incident management | audit-service **is** the evidence store other services emit to |
| Art. 17 | Reporting | trail provides the immutable evidence basis for major-incident reports |
| Art. 28 | Third-party risk | no third-party SaaS — PostgreSQL/Kafka self-hosted in-cluster |

## Audit-of-the-auditor — integrity controls

- **Immutability by construction** — PostgreSQL `DO INSTEAD NOTHING` rules on UPDATE/DELETE; the integrity of the trail does not depend on application discipline.
- **Append-only correction** — mistakes are corrected by appending, preserving the original record.
- **K7 access control** — read API is never `@PermitAll`; gated to `ROLE_AUDITOR` / `ROLE_ADMIN` / `ROLE_COMPLIANCE`, locked by the `AuditResourceSecurityTest` regression guard.
- **No write API** — entries can only arrive via Kafka from authentic platform producers; there is no operator-facing insert path to forge entries.

## Security controls

- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@RolesAllowed` (auditor/admin/compliance), K7-guarded
- ✅ Immutable storage: DB-level UPDATE/DELETE rejection
- ✅ Rate limiting: `openbank.rate-limit` (200 concurrent)
- ✅ Security headers: CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff, referrer/permissions policy
- ✅ Resilience: outbox dispatcher with bulkhead/circuit-breaker/retry/timeout
- ✅ Observability: Prometheus + OpenTelemetry + SmallRye Health
- ✅ Secrets: env-injected, dev placeholders never shipped (Vault, ADR-0017)
- ⚠️ Outbound re-emit channel (`audit-events-out`) not yet configured — dormant ([05](./05-operations.md))
- ✅ `governance.yaml` datastore fields match the code (`primaryDatastore: PostgreSQL`, `databaseName: openbank_audit`; tables in `public`)
