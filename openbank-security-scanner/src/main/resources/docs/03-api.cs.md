# API & kontrakty

## Base path

- **Produkční base:** `http://openbank-security-scanner:8120/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8120/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8120/q/swagger-ui)

## Autentizace

**OIDC je vypnuto** pro tuto službu — je to interní platformový nástroj volaný pouze in-cluster z admin-ui. Všechny endpointy jsou otevřené v rámci clusterové sítě. Bearer token není vyžadován.

> Do budoucna: plánována service-to-service autentizace ROLE_PLATFORM_INTERNAL.

## Endpointy bezpečnostního skenování

### Spustit úplný sken

```http
POST /api/v1/security/scan
```

Spustí okamžitý sken všech 27 konfigurovaných fleet služeb. Běží synchronně (může trvat 30–90s pro celý fleet). Vrátí `PlatformSecurityReport`.

### Získat nejnovější platformový report

```http
GET /api/v1/security/report
```

Vrátí naposledy dokončený `PlatformSecurityReport` z in-memory cache.

```http
404 Not Found — pokud žádný sken ještě nebyl dokončen
{ "message": "No scan completed yet. POST /api/v1/security/scan to trigger." }
```

### Získat všechny výsledky služeb

```http
GET /api/v1/security/services
```

Vrátí pole `ServiceScanResult` pro všechny služby z in-memory cache.

### Získat výsledek pro konkrétní službu

```http
GET /api/v1/security/services/{name}
```

`name` = název služby dle konfigurace (např. `account-service`, `sanctions-service`).

**Příklad výsledku služby:**

```json
{
  "serviceName": "account-service",
  "score": 75,
  "grade": "C",
  "reachable": true,
  "criticalCount": 0,
  "highCount": 1,
  "mediumCount": 5,
  "findings": [
    {
      "id": "CORS_WILDCARD",
      "category": "A05_SECURITY_MISCONFIGURATION",
      "severity": "HIGH",
      "title": "CORS wildcard origin allowed",
      "remediation": "Restrict CORS to known origins only",
      "cweId": "CWE-942"
    }
  ]
}
```

### Info skeneru

```http
GET /api/v1/security/info
```

Vrátí schopnosti skeneru, verzi a pokryté compliance standardy.

## Endpointy ICT incidentů

### Nahlásit nový ICT incident

```http
POST /api/v1/ict-incidents
Content-Type: application/json

{
  "title": "account-service nereaguje — health check selhává",
  "description": "Všechny /q/health sondy vrací 503 od 08:14 UTC.",
  "category": "AVAILABILITY",
  "severity": "P2_HIGH",
  "affectedServices": ["account-service", "balance-service"],
  "detectedAt": "2026-06-05T08:14:00Z",
  "assignedTo": "sre@openbank.example"
}
```

### Seznam incidentů

```http
GET /api/v1/ict-incidents?status=OPEN&severity=P1_CRITICAL&limit=50&offset=0
```

Query parametry: `status` (OPEN / INVESTIGATING / CONTAINED / RESOLVED / CLOSED), `severity` (P1_CRITICAL / P2_HIGH / P3_MEDIUM / P4_LOW), `limit`, `offset`.

### Získat konkrétní incident

```http
GET /api/v1/ict-incidents/{id}
```

### Aktualizovat stav incidentu

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

`rtoMinutes` = skutečně dosažený Recovery Time Objective. `rpoMinutes` = skutečně dosažený Recovery Point Objective.

### Označit jako nahlášený regulátorovi

```http
POST /api/v1/ict-incidents/{id}/regulatory-report
Content-Type: application/json

{
  "regulatoryReportId": "CNB-DORA-2026-001234"
}
```

Povinné pro P1_CRITICAL incidenty (nahlásit ČNB do 4 hodin) a P2_HIGH (do 24 hodin).

## Doménové modely

### Hodnoty IncidentCategory

`AVAILABILITY | INTEGRITY | CONFIDENTIALITY | AUTHENTICITY | UNAUTHORIZED_ACCESS | DATA_BREACH | RANSOMWARE | DDOS | INSIDER_THREAT | SUPPLY_CHAIN | OTHER`

### Životní cyklus IncidentStatus

```
OPEN → INVESTIGATING → CONTAINED → RESOLVED → CLOSED
```

### Klíče compliance stavu v PlatformSecurityReport

| Klíč | Co kontroluje |
|---|---|
| `PSD2_SCA` | Všechny služby jsou dosažitelné |
| `EBA_ICT_RISK` | ≥ 80 % služeb skóruje ≥ 70 |
| `GDPR_DATA_PROTECT` | Žádná CRITICAL zjištění v A02 (kryptografická selhání) |
| `OWASP_TOP10` | Žádná CRITICAL zjištění napříč všemi službami |
| `CNB_SECURITY` | Skóre platformy ≥ 70 |

## Eventy

Eventy ICT incidentů vysílá přímo do Kafky SmallRye `@Channel` emitter — žádný outbox ani transakční
záruka. Skeny nevysílají nic: platformový report je pouze přes REST (#4709).

| Topic | Typ eventu | Spuštění | Klíčová pole |
|---|---|---|---|
| `openbank.security.ict.incident` | `ict.incident.reported.v1` | POST /ict-incidents | id, title, severity, category, affectedServices, detectedAt |
| `openbank.security.ict.incident` | `ict.incident.updated.v1` | PATCH /status | id, newStatus, containedAt, resolvedAt, rtoMinutes |
| `openbank.security.ict.incident` | `ict.incident.regulatory.reported.v1` | POST /regulatory-report | id, regulatoryReportId, reportedAt |
