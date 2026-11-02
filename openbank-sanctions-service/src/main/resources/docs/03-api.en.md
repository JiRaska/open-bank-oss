# API & contracts

## Base path

- **Production base for checks:** `http://openbank-sanctions-service:8123/api/v3` (in-cluster)
- **List administration and approvals:** remain under `/api/v1` and `/api/v2` as documented below
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8123/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8123/q/swagger-ui)

### Migrating checks from v1 to v3

`CZ_TERRORISTS` is a new value in screening requests and responses, so the six operations that
return `SanctionsCheck` have parallel `/api/v3` contracts: list checks, screen, review, hits,
pending and get by ID. Migrate those calls to v3; list administration, refresh and approval URLs
are not affected.

The superseded v1 operations return `Deprecation: true`, an RFC 8594 `Sunset` header and a v3
`Link` with `rel="successor-version"`. Their contract remains fixed during the migration window:

- omitted, null or empty `listTypes` means the original seven lists, excluding `CZ_TERRORISTS`;
  v3 means all eight lists;
- v1 rejects an explicit `CZ_TERRORISTS` request and a cross-major idempotent replay with 400;
- checks containing the new list are hidden from v1 collections, and v1 get/review answers 404
  before changing them. Use the same operation under v3 to read or review them.

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
POST /api/v3/sanctions/screen
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
  "checkedLists": ["OFAC_SDN", "EU_CONSOLIDATED", "UN_CONSOLIDATED", "HM_TREASURY", "FATF_HIGH_RISK", "PEP_GLOBAL", "CNB_DOMESTIC", "CZ_TERRORISTS"],
  "checkedAt": "2026-06-05T10:42:13Z"
}
```

**Decision logic for callers:**
- `CLEAR` or `WHITELISTED` → proceed with the payment/account operation
- `POTENTIAL_HIT` → block pending manual compliance review
- `HIT` or `ESCALATED` → block; do not proceed

**Subject-bound clearance (#11694):**
- Send the stable party identifier as optional `subjectId`, separately from the per-attempt
  `idempotencyKey`. A new account-opening attempt uses a new key and is screened against the
  current lists; only an identical key replays the original result.
- A `WHITELISTED` review is valid for 90 days (`clearanceExpiresAt`) and only for exactly the same
  `subjectId`, entity type, name, aliases, date of birth, nationality and identifiers.
- The clearance covers only the reviewed match identities: `(listType, matchedId)`, or
  `(listType, matchedName)` when the entry has no external id. A new uncovered hit, changed subject
  or expired clearance keeps the fresh blocking result.
- Legacy checks without `subjectId` cannot newly receive `WHITELISTED`. Screen the subject again
  with its explicit id and review that new check instead.

### Submit a manual review decision

```http
POST /api/v3/sanctions/review
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
GET /api/v3/sanctions/{id}
Authorization: Bearer <token>
```

### List all confirmed hits

```http
GET /api/v3/sanctions/hits
Authorization: Bearer <token>
```

### List pending reviews

```http
GET /api/v3/sanctions/pending
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

`listType` ∈ `OFAC_SDN | EU_CONSOLIDATED | UN_CONSOLIDATED | HM_TREASURY | FATF_HIGH_RISK | CNB_DOMESTIC | CZ_TERRORISTS`

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
| `CNB_DOMESTIC` | Czech Ministry of Foreign Affairs (MZV) | Czech national sanctions list (Act No. 1/2023 Coll.) |
| `CZ_TERRORISTS` | Czech e-Sbírka | Anti-terrorism designations (Regulation No. 210/2008 Coll.), imported from official SPARQL open data |

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

- API version in URL. A breaking contract change gets a new parallel major; the six v1 check
  operations are superseded by `/api/v3/...` and remain available for the documented sunset window.
- Event version in type name (`...v1`). Schema evolution: additive only; breaking = new type + version suffix.
