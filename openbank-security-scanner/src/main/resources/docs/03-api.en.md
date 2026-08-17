# API & contracts

## Base path

- **Production base:** `http://openbank-security-scanner:8120/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8120/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8120/q/swagger-ui)

## Authentication

**OIDC is disabled** for this service — it is an internal platform tool called only in-cluster by admin-ui. All endpoints are open within the cluster network. No Bearer token required.

> Future: ROLE_PLATFORM_INTERNAL service-to-service auth planned.

## Security scan endpoints

### Trigger a full scan

```http
POST /api/v1/security/scan
```

Triggers an immediate scan of all 27 configured fleet services. Runs synchronously (may take 30–90s for full fleet). Returns a `PlatformSecurityReport`.

```http
200 OK
Content-Type: application/json

{
  "reportId": "a1b2c3d4-...",
  "generatedAt": "2026-06-05T08:00:00Z",
  "totalServices": 27,
  "reachableServices": 26,
  "platformScore": 82,
  "platformGrade": "B",
  "criticalFindings": 1,
  "highFindings": 3,
  "owaspCoverage": {
    "A01_BROKEN_ACCESS_CONTROL": 4,
    "A02_CRYPTOGRAPHIC_FAILURES": 0,
    "A05_SECURITY_MISCONFIGURATION": 18,
    ...
  },
  "complianceStatus": {
    "PSD2_SCA": true,
    "EBA_ICT_RISK": true,
    "GDPR_DATA_PROTECT": true,
    "OWASP_TOP10": false,
    "CNB_SECURITY": true
  },
  "serviceResults": [ ... ]
}
```

### Get latest platform report

```http
GET /api/v1/security/report
```

Returns the most recently completed `PlatformSecurityReport` from in-memory cache.

```http
404 Not Found — if no scan has completed yet
{ "message": "No scan completed yet. POST /api/v1/security/scan to trigger." }
```

### Get all service results

```http
GET /api/v1/security/services
```

Returns an array of `ServiceScanResult` for all services from the in-memory cache.

### Get result for a specific service

```http
GET /api/v1/security/services/{name}
```

`name` = service name as configured (e.g. `account-service`, `sanctions-service`).

```http
200 OK

{
  "serviceName": "account-service",
  "serviceUrl": "http://account-service:8100",
  "scannedAt": "2026-06-05T08:00:05Z",
  "durationMs": 342,
  "reachable": true,
  "score": 75,
  "grade": "C",
  "tlsVersion": "TLS 1.3",
  "openApiAvailable": true,
  "healthEndpointSecured": false,
  "criticalCount": 0,
  "highCount": 1,
  "mediumCount": 5,
  "headersPresent": {
    "x-content-type-options": false,
    "x-frame-options": true,
    "strict-transport-security": false,
    "content-security-policy": false,
    "x-xss-protection": true,
    "referrer-policy": false,
    "permissions-policy": false
  },
  "findings": [
    {
      "id": "CORS_WILDCARD",
      "category": "A05_SECURITY_MISCONFIGURATION",
      "severity": "HIGH",
      "title": "CORS wildcard origin allowed",
      "description": "Service allows requests from any origin (Access-Control-Allow-Origin: *)",
      "remediation": "Restrict CORS to known origins only",
      "cweId": "CWE-942",
      "cvssScore": 6.5,
      "endpoint": "http://account-service:8100",
      "evidence": "ACAO: *"
    }
  ]
}
```

### Scanner info

```http
GET /api/v1/security/info
```

Returns scanner capabilities, version, and compliance standards covered.

```json
{
  "service": "openbank-security-scanner",
  "version": "0.1.0",
  "capabilities": ["owasp-top10", "security-headers", "cors-check", "actuator-exposure", "tls-check"],
  "standards": ["OWASP Top 10 2021", "EBA ICT Risk Guidelines", "PSD2 RTS", "NIST SP 800-53"]
}
```

## ICT incident endpoints

### Report a new ICT incident

```http
POST /api/v1/ict-incidents
Content-Type: application/json

{
  "title": "account-service unresponsive — health check failing",
  "description": "All /q/health probes returning 503 since 08:14 UTC. Liveness probe failing.",
  "category": "AVAILABILITY",
  "severity": "P2_HIGH",
  "affectedServices": ["account-service", "balance-service"],
  "detectedAt": "2026-06-05T08:14:00Z",
  "assignedTo": "sre@openbank.example"
}
```

```http
201 Created

{
  "id": "b3c4d5e6-...",
  "title": "account-service unresponsive — health check failing",
  "status": "OPEN",
  "severity": "P2_HIGH",
  "category": "AVAILABILITY",
  "affectedServices": ["account-service", "balance-service"],
  "detectedAt": "2026-06-05T08:14:00Z",
  "reportedAt": "2026-06-05T08:16:00Z",
  "reportedToRegulator": false,
  "createdAt": "2026-06-05T08:16:00Z",
  "updatedAt": "2026-06-05T08:16:00Z"
}
```

### List incidents

```http
GET /api/v1/ict-incidents?status=OPEN&severity=P1_CRITICAL&limit=50&offset=0
```

Query parameters: `status` (OPEN / INVESTIGATING / CONTAINED / RESOLVED / CLOSED), `severity` (P1_CRITICAL / P2_HIGH / P3_MEDIUM / P4_LOW), `limit`, `offset`.

### Get a specific incident

```http
GET /api/v1/ict-incidents/{id}
```

### Update incident status

```http
PATCH /api/v1/ict-incidents/{id}/status
Content-Type: application/json

{
  "status": "RESOLVED",
  "containedAt": "2026-06-05T08:45:00Z",
  "resolvedAt": "2026-06-05T09:30:00Z",
  "rtoMinutes": 76,
  "rpoMinutes": 20
}
```

`rtoMinutes` = actual Recovery Time Objective achieved. `rpoMinutes` = actual Recovery Point Objective achieved.

### Mark as reported to regulator

```http
POST /api/v1/ict-incidents/{id}/regulatory-report
Content-Type: application/json

{
  "regulatoryReportId": "CNB-DORA-2026-001234"
}
```

Required for P1_CRITICAL incidents (report to CNB within 4 hours) and P2_HIGH (within 24 hours).

## Domain models

### IncidentCategory values

`AVAILABILITY | INTEGRITY | CONFIDENTIALITY | AUTHENTICITY | UNAUTHORIZED_ACCESS | DATA_BREACH | RANSOMWARE | DDOS | INSIDER_THREAT | SUPPLY_CHAIN | OTHER`

### IncidentStatus lifecycle

```
OPEN → INVESTIGATING → CONTAINED → RESOLVED → CLOSED
```

### Compliance status keys in PlatformSecurityReport

| Key | What it checks |
|---|---|
| `PSD2_SCA` | All services are reachable |
| `EBA_ICT_RISK` | ≥ 80% of services score ≥ 70 |
| `GDPR_DATA_PROTECT` | No CRITICAL findings in A02 (cryptographic failures) |
| `OWASP_TOP10` | No CRITICAL findings across all services |
| `CNB_SECURITY` | Platform score ≥ 70 |

## Events

ICT incident events are emitted directly to Kafka by a SmallRye `@Channel` emitter — there is no
outbox and no transactional guarantee. Scans emit nothing: the platform report is REST-only (#4709).

| Topic | Event type | Trigger | Key fields |
|---|---|---|---|
| `openbank.security.ict.incident` | `ict.incident.reported.v1` | POST /ict-incidents | id, title, severity, category, affectedServices, detectedAt |
| `openbank.security.ict.incident` | `ict.incident.updated.v1` | PATCH /status | id, newStatus, containedAt, resolvedAt, rtoMinutes |
| `openbank.security.ict.incident` | `ict.incident.regulatory.reported.v1` | POST /regulatory-report | id, regulatoryReportId, reportedAt |
