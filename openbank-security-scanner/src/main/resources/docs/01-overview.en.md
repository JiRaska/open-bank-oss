# Overview

## What the service does

`openbank-security-scanner` is the **automated security posture monitor** for the OpenBank platform. It:

- **Probes all 27 fleet services** every 30 minutes via HTTP, checking reachability through `/q/health`, security headers on the API port, and actuator exposure.
- **Runs 6 OWASP Top 10 checks** on each service: security headers (A05), sensitive data in health endpoint (A02), OpenAPI exposure (A05 info-disclosure), unauthenticated actuator endpoints (A01), CORS wildcard misconfiguration (A05), and service reachability (A05).
- **Scores each service** 0–100 and assigns a letter grade (A+ → F), computing a `PlatformSecurityReport` covering all services.
- **Manages ICT incidents** — compliance officers can report, track, and update the lifecycle of DORA-grade ICT incidents through the `IctIncidentResource` API. Incidents are persisted in the `ict_incidents` register and remain queryable across pod restarts.
- **Emits ICT incident events** directly to the Kafka topic `openbank.security.ict.incident`. There is no outbox and no transactional guarantee; scan results are not published as events at all.

## What the service does NOT do

- Does not authenticate or authorise end users — it is an internal platform tool with OIDC disabled.
- Does not perform DAST (Dynamic Application Security Testing) or fuzzing — HTTP-level black-box probes only.
- Does not scan infrastructure or network layer — application-layer HTTP checks only.
- Does not enforce remediation — it reports findings; humans and processes own the response.
- Does not store full HTTP response bodies — only metadata, findings, headers presence map, and scores.
- Does not persist anything. Scan results and ICT incidents are in-memory only and are lost on pod restart (see [04 — Data](./04-data.md)).

## Position in the domain

```
                      ┌─── every 30 min ───────────────────────────────┐
                      ▼                                                 │
   ┌──────────────────────────────┐     scan results     ┌─────────────────┐
   │     security-scanner         │ ──────────────────►  │   admin-ui      │
   │     (this service)           │                      │ (security page) │
   └──────────────┬───────────────┘                      └─────────────────┘
                  │ direct Kafka emitter (ICT incidents only)
                  ▼
         openbank.security.ict.incident
                  │
         ┌────────▼────────┐
         │  audit-service  │
         └─────────────────┘

   Probes ──► all 27 fleet services (via /q/health + API port HTTP)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Trigger an on-demand full scan | `POST /api/v1/security/scan` | — (no event; report is REST-only) |
| Get the latest platform report | `GET /api/v1/security/report` | — |
| Get results for a specific service | `GET /api/v1/security/services/{name}` | — |
| Report a new ICT incident | `POST /api/v1/ict-incidents` | `IctIncidentReported` |
| Update incident status | `PATCH /api/v1/ict-incidents/{id}/status` | `IctIncidentUpdated` |
| Mark incident as reported to regulator | `POST /api/v1/ict-incidents/{id}/regulatory-report` | `IctIncidentRegulatoryReported` |

## Scan checks per service

| Check | OWASP Category | Severity if failed |
|---|---|---|
| Service unreachable | A05 — Security Misconfiguration | CRITICAL |
| Missing `X-Content-Type-Options` | A05 | MEDIUM |
| Missing `X-Frame-Options` | A05 | MEDIUM |
| Missing `Strict-Transport-Security` | A05 | MEDIUM |
| Missing `Content-Security-Policy` | A05 | MEDIUM |
| Missing `X-XSS-Protection` | A05 | MEDIUM |
| Missing `Referrer-Policy` | A05 | MEDIUM |
| Missing `Permissions-Policy` | A05 | MEDIUM |
| Sensitive data in health endpoint | A02 — Cryptographic Failures | HIGH |
| OpenAPI spec exposed without auth | A05 (info-disclosure) | INFO |
| Unauthenticated `/q/metrics` on API port | A01 — Broken Access Control | MEDIUM |
| Unauthenticated `/q/info` on API port | A01 | MEDIUM |
| Unauthenticated `/q/dev` on API port | A01 | MEDIUM |
| CORS wildcard (`*`) origin | A05 | HIGH |

## Scoring formula

```
score = max(0, 100 - criticals × 30 - highs × 15 - mediums × 5)
```

Grades:
- A+ ≥ 95 | A ≥ 90 | B ≥ 80 | C ≥ 70 | D ≥ 60 | F < 60

`platformScore` = average of scores for all reachable services.

## ICT incident severity levels (DORA Art. 17)

| Level | Description | Reporting obligation |
|---|---|---|
| `P1_CRITICAL` | Platform-wide outage, data breach, ransomware | Report to CNB within 4 hours of detection |
| `P2_HIGH` | Multiple services affected, SLA breach | Report to CNB within 24 hours |
| `P3_MEDIUM` | Single service degradation | Internal tracking only |
| `P4_LOW` | Minor finding, no customer impact | Internal tracking only |

## Dependencies

- **PostgreSQL** (`openbank-postgres`, schema `openbank_security`) — Flyway schema history only, no business tables
- **Kafka** (`openbank-kafka`, topic `openbank.security.ict.incident`)
- **All 27 fleet services** — probed via HTTP (read-only, no auth required for `/q/health`)
- **openbank-libs** ≥ 0.1.0 — BuildInfo, DocsResource
