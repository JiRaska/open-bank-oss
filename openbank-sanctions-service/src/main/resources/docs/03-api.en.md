# API & contracts

## Base path

- **Production base:** `http://openbank-sanctions-service:8123/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8123/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8123/q/swagger-ui)

## Authentication

All endpoints require a **Keycloak Bearer token** with realm `openbank`. Mutating operations additionally require `ROLE_OPERATOR`:

| Role | Rights |
|---|---|
| `ROLE_VIEWER` | GET only (list checks, hits, pending, lists) |
| `ROLE_OPERATOR` | GET + screen + review + list management |
| `ROLE_COMPLIANCE` | GET + screen + review (primary compliance-officer role) |
| `ROLE_ADMIN` | everything |

## Idempotency

All **POST** screening requests require an `idempotencyKey` in the request body (not a header, unlike other services):

```json
{
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  ...
}
```

Rules:
- Client generates a UUID v4 per logical screening request.
- Same key → returns the original `SanctionsCheck` result (replay-safe, cached in Redis).
- The key is stored in `sanctions_checks.idempotency_key` (UNIQUE constraint).

## Key endpoints

### Screen an entity

```http
POST /api/v1/sanctions/screen
Content-Type: application/json
Authorization: Bearer <token>

{
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "entityType": "INDIVIDUAL",
  "name": "John Smith",
  "aliases": ["Johnny Smith", "J. Smith"],
  "dateOfBirth": "1975-03-14",
  "nationality": "RU",
  "identifiers": {
    "passport": "789012345",
    "taxId": "CZ1234567890"
  }
}
```

```http
201 Created
Content-Type: application/json

{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "entityType": "INDIVIDUAL",
  "name": "John Smith",
  "aliases": ["Johnny Smith", "J. Smith"],
  "dateOfBirth": "1975-03-14",
  "nationality": "RU",
  "identifiers": { "passport": "789012345" },
  "status": "POTENTIAL_HIT",
  "overallScore": 0.91,
  "matches": [
    {
      "listType": "OFAC_SDN",
      "matchType": "FUZZY",
      "matchScore": 0.91,
      "matchedName": "JOHN SMYTH",
      "matchedId": "OFAC-12345",
      "listEntryDate": "2023-11-01",
      "programs": ["UKRAINE-EO13685"]
    }
  ],
  "checkedLists": ["OFAC_SDN", "EU_CONSOLIDATED", "UN_CONSOLIDATED", "HM_TREASURY", "FATF_HIGH_RISK", "CNB_DOMESTIC"],
  "checkedAt": "2026-06-05T10:42:13Z"
}
```

**Decision logic for callers:**
- `CLEAR` or `WHITELISTED` → proceed with the payment/account operation
- `POTENTIAL_HIT` → block pending manual compliance review
- `HIT` or `ESCALATED` → block; do not proceed

### Submit a manual review decision

```http
POST /api/v1/sanctions/review
Content-Type: application/json
Authorization: Bearer <token>

{
  "checkId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reviewedBy": "compliance@openbank.example",
  "note": "Different person confirmed by passport check — cleared.",
  "newStatus": "CLEAR"
}
```

`newStatus` ∈ `CLEAR | HIT | POTENTIAL_HIT | WHITELISTED | ESCALATED`

### Get a specific check

```http
GET /api/v1/sanctions/{id}
Authorization: Bearer <token>
```

### List all confirmed hits

```http
GET /api/v1/sanctions/hits
Authorization: Bearer <token>
```

### List pending reviews

```http
GET /api/v1/sanctions/pending
Authorization: Bearer <token>
```

### List sanctions list configurations

```http
GET /api/v1/sanctions/lists
Authorization: Bearer <token>
```

### Update a sanctions list configuration

```http
PUT /api/v1/sanctions/lists/{id}
Content-Type: application/json
Authorization: Bearer <token>

{
  "enabled": true,
  "sourceUrl": "https://www.treasury.gov/ofac/downloads/sdn.xml",
  "cronHour": 6,
  "cronMinute": 0,
  "cronDays": "MON,TUE,WED,THU,FRI"
}
```

### Trigger a manual list refresh

```http
POST /api/v1/sanctions/lists/{listType}/refresh
Authorization: Bearer <token>
```

`listType` ∈ `OFAC_SDN | EU_CONSOLIDATED | UN_CONSOLIDATED | HM_TREASURY | FATF_HIGH_RISK | CNB_DOMESTIC`

### Refresh all enabled lists

```http
POST /api/v1/sanctions/lists/refresh-all
Authorization: Bearer <token>
```

## Entity types

| EntityType | Description |
|---|---|
| `INDIVIDUAL` | Natural person |
| `ORGANIZATION` | Legal entity, company |
| `VESSEL` | Ship or maritime vessel |
| `AIRCRAFT` | Aircraft by tail number or ICAO designation |

## Sanctions list types

| ListType | Authority | Scope |
|---|---|---|
| `OFAC_SDN` | US Treasury OFAC | Specially Designated Nationals — global |
| `EU_CONSOLIDATED` | EU Council | Consolidated EU sanctions list |
| `UN_CONSOLIDATED` | UN Security Council | UN consolidated list |
| `HM_TREASURY` | UK HM Treasury | UK Financial Sanctions |
| `FATF_HIGH_RISK` | FATF | High-risk and monitored jurisdictions |
| `CNB_DOMESTIC` | Czech National Bank | Domestic Czech sanctions |

## Error model

```json
{
  "code": "sanctions-check-not-found",
  "message": "No sanctions check with ID 3fa85f64-..."
}
```

| HTTP | code | When |
|---|---|---|
| 400 | `validation-failed` | Missing required fields (idempotencyKey, name, entityType) |
| 401 | `unauthorized` | Missing / invalid Keycloak token |
| 403 | `forbidden` | Role missing for the endpoint |
| 404 | `sanctions-check-not-found` | Check ID does not exist |
| 404 | `sanctions-list-not-found` | List ID does not exist |
| 409 | `idempotency-key-conflict` | Same key, different payload |
| 500 | `internal-error` | Unexpected error |

## Events

Topic: `openbank.sanctions.screening.event` (CloudEvents binding, JSON).

| Event type | Trigger | Key payload fields |
|---|---|---|
| `sanctions.check.completed.v1` | POST /screen | id, entityType, name, status, overallScore, checkedLists, checkedAt |
| `sanctions.review.submitted.v1` | POST /review | checkId, reviewedBy, newStatus, reviewNote, reviewedAt |

Events are append-only; a subsequent review creates a new `sanctions.review.submitted.v1` event, it does not overwrite the original check event.

## Backward compatibility

- API version in URL (`/api/v1/...`). Breaking changes = `/api/v2/...`, v1 runs in parallel for 6 months.
- Event version in type name (`...v1`). Schema evolution: additive only; breaking = new type + version suffix.
