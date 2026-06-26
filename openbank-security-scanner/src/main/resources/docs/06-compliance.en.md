# Compliance

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **DORA (EU 2022/2554)** | Primary regulatory mandate: ICT risk management, incident detection, reporting | ICT incident lifecycle API, P1/P2 reporting to CNB, `PlatformSecurityReport` as ICT risk evidence |
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
| Art. 23 | Supervisory reporting | `regulatoryReportId` links to CNB submission; audit trail via outbox |
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
- Monitoring of security events (covered: findings published to audit-service)

## Security controls of the scanner itself

- OIDC disabled (internal tool; no external attack surface for auth bypass)
- Network policy: scanner can only be reached from admin-ui and cluster-internal services
- No customer data stored or processed
- Outbox guarantees: all security events reach audit-service even if Kafka is temporarily unavailable
- BootstrapVerifier blocks dev DB passwords in production profile

## Audit trail

Every scan result and ICT incident lifecycle change is:
1. Written to `security_outbox`
2. Published to Kafka topics (`openbank.security.scan.event`, `openbank.security.ict.incident`)
3. Consumed by `audit-service` for tamper-evident 10-year retention

This provides the regulatory evidence trail required by DORA Art. 17 and CNB inspection requirements.
