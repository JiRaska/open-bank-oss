# Compliance

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **DORA (EU 2022/2554)** | Primary regulatory mandate: ICT risk management, incident detection, reporting | ICT incident lifecycle API, P1/P2 reporting to CNB, `PlatformSecurityReport` as a live ICT risk signal (not retained evidence — see Audit trail) |
| **EBA ICT Risk Guidelines (EBA/GL/2019/04)** | ICT security testing requirements | OWASP Top 10 automated checks every 30 min; `EBA_ICT_RISK` compliance flag in platform report |
| **PSD2 RTS (EU 2018/389)** | Strong security requirements for payment infrastructure | `PSD2_SCA` compliance flag: all services reachable; security findings in payment-path services trigger alerts |
| **NIS2 (EU 2022/2555)** | Network and information security for essential entities | Fleet-wide health monitoring, incident detection and reporting pipeline |
| **NIST SP 800-53** | Security controls framework | Check mapping to NIST controls (see OWASP / NIST cross-reference below) |
| **GDPR (EU 2016/679)** | Operational data in ICT incidents (assigned_to email) | Internal employee data only; not customer PII; no erasure obligation |
| **CNB Decree 163/2014** | Czech National Bank domestic security requirements | `CNB_SECURITY` flag (platform score ≥ 70); DORA reports to CNB |

## DORA mapping (Reg. (EU) 2022/2554)

This service implements several DORA obligations directly:

| Article | Topic | Implementation in security-scanner |
|---|---|---|
| Art. 5 | ICT risk management | service is the ICT risk monitoring tool for the platform |
| Art. 9 | Identification of ICT assets | `GET /api/v1/security/services` lists all monitored ICT assets |
| Art. 10 | Detection of anomalies | Scheduled 30-min scans detect regressions; CRITICAL findings trigger alerts |
| Art. 11 | Response & recovery | `IctIncident` lifecycle (OPEN→RESOLVED), RTO/RPO tracking |
| Art. 17 | ICT incident reporting | Full incident reporting workflow: `POST /ict-incidents` → `PATCH /status` → `POST /regulatory-report` |
| Art. 23 | Supervisory reporting | `regulatoryReportId` links to CNB submission; the record is in-memory only (see limitations below) |
| Art. 24 | ICT risk testing | OWASP Top 10 automated test suite as the digital operational resilience test |
| Art. 28 | Third-party risk | Scanner probes include third-party-integrated services (Keycloak, Kafka health) |

### DORA ICT incident reporting SLA

| Severity | Initial report | Intermediate report | Final report |
|---|---|---|---|
| `P1_CRITICAL` | CNB within 4 hours | Every 24 hours until resolved | Within 1 month of resolution |
| `P2_HIGH` | CNB within 24 hours | Every 3 days until resolved | Within 1 month of resolution |
| `P3_MEDIUM` | Internal only | — | — |
| `P4_LOW` | Internal only | — | — |

## OWASP Top 10 2021 — compliance mapping

| OWASP Category | Checks performed | NIST SP 800-53 |
|---|---|---|
| A01 — Broken Access Control | Unauthenticated actuator endpoints (`/q/metrics`, `/q/info`, `/q/dev`) on API port | AC-3, AC-17 |
| A02 — Cryptographic Failures | Sensitive data (password/secret strings) in health endpoint responses | SC-8, SC-28 |
| A03 — Injection | Not checked (HTTP-level black-box only) | — |
| A04 — Insecure Design | Not checked | — |
| A05 — Security Misconfiguration | Missing security headers (7 checks), CORS wildcard, OpenAPI exposure, unreachable service | CM-6, CM-7 |
| A06 — Vulnerable Components | Not checked (SCA is in CI pipeline via CycloneDX SBOM) | SI-2 |
| A07 — Authentication Failures | Not directly checked by scanner (auth covered by OIDC layer) | IA-2, IA-8 |
| A08–A10 | Not in scope for HTTP black-box checks | — |

## EBA ICT Risk Guidelines mapping

The `EBA_ICT_RISK` compliance flag in `PlatformSecurityReport.complianceStatus` is `true` when ≥ 80% of services score ≥ 70.

EBA/GL/2019/04 Section 3.3 (ICT security testing) requires:
- Periodic vulnerability assessments of IT systems (covered: scheduled OWASP checks)
- Scenario-based testing (partial: HTTP-level; penetration testing is separate)
- Monitoring of security events (partial: findings are readable over REST but are not published anywhere)

## Security controls of the scanner itself

- OIDC disabled (internal tool; no external attack surface for auth bypass)
- Network policy: scanner can only be reached from admin-ui and cluster-internal services
- No customer data stored or processed
- ⬜ **`BootstrapVerifier` does not exist** — nothing blocks a dev DB password at startup. The credential reaches the pod through `secretKeyRef` from ESO/OpenBao in `security-scanner-service.yaml` (ADR-0007), which is a configuration property, not a control in the application (#8426)
- The service still holds a Kafka producer identity (KafkaUser + mTLS) for the ICT incident topic

## Audit trail — what actually exists

Stated plainly, because this is where the documentation previously overclaimed (#4709):

| Artefact | How it is recorded | Durability |
|---|---|---|
| Scan results / `PlatformSecurityReport` | `GET /api/v1/security/report` (REST) only — no event, no database row | None. In-memory; lost on pod restart |
| ICT incident lifecycle | Kafka `openbank.security.ict.incident`, emitted directly by a `@Channel` emitter | Whatever `audit-service` retains from the topic; the service itself keeps no copy |

Limitations that must not be papered over:

- There is **no transactional guarantee** on ICT incident events. The emit is fire-and-forget; if the
  publish fails, the incident exists only in the pod's memory and no retry is possible.
- There is **no persistent incident register**. A pod restart loses every incident that has not
  already been consumed downstream.
- The scan report is **not** part of any tamper-evident audit trail. It is a live read of the last
  scan, not evidence of past scans.

For DORA Art. 17 evidence purposes the durable artefact is the audit-service record derived from the
ICT incident topic. Anything stronger — a durable incident register, or scan history usable as
evidence of continuous monitoring — is a gap, not a control this service provides today.
