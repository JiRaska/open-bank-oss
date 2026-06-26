# API & contracts

The REST contract is formalised in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.0.0`). All endpoints are **read-only GETs** over the onboarding read-model.

## Base path

- **Production base:** `http://openbank-onboarding-service:8130/api/v1` (in-cluster)
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (`quarkus.swagger-ui.always-include=true`)

## Authentication

The service is wired to **Keycloak OIDC** (realm `openbank`, client `openbank-services`). A valid Bearer token is required when OIDC is enabled (it is disabled in the `%dev` and `%test` profiles).

> **Authorization status (TBD vs. ADR-0068):** ADR-0068 §7 specifies that every onboarding endpoint ships `@Authorize`/OPA in **enforce** mode from day one, with UI permissions `onboarding:view`, `onboarding:act.soft`, `onboarding:decide`, `onboarding:override`. The current `OnboardingResource` carries **no `@RolesAllowed`/`@Authorize` annotations** — authentication is enforced, but per-role authorization is not yet wired. This is a known gap to close before the cockpit goes to production.

## Idempotency

Not applicable. All three endpoints are read-only GETs, so there is no `Idempotency-Key` requirement. On the ingestion side, event projection is upsert-by-`party_id` and therefore naturally idempotent — replaying an event produces the same row.

## Endpoints

### List onboarding records

```http
GET /api/v1/onboarding/records?page=0&size=20&stage=KYC_OPEN
Authorization: Bearer <token>
```

Query parameters:

| Param | Type | Default | Notes |
|---|---|---|---|
| `page` | integer | `0` | zero-based page index |
| `size` | integer | `20` | clamped server-side to `1..100` (`size.coerceIn(1, 100)`) |
| `stage` | string | — | optional funnel-stage filter; case-insensitive, unrecognised values are ignored (treated as no filter) |

`stage` ∈ `REGISTERED`, `KYC_OPEN`, `KYC_DOCUMENTS_REQUIRED`, `KYC_UNDER_REVIEW`, `SCA_PENDING`, `ACTIVE`, `BLOCKED`.

```http
200 OK
Content-Type: application/json

{
  "items": [
    {
      "partyId": "b1f9…",
      "legalName": "Jane Doe",
      "email": "jane@example.com",
      "partyStatus": "PENDING_KYC",
      "kycCaseId": null,
      "kycStatus": null,
      "scaEnrolled": false,
      "deviceCount": 0,
      "funnelStage": "REGISTERED",
      "blockedReason": null,
      "createdAt": "2026-06-09T10:42:13Z",
      "updatedAt": "2026-06-09T10:42:13Z"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20,
  "stageFilter": "KYC_OPEN"
}
```

`stageFilter` is present only when a `stage` filter was applied.

### Get onboarding record for a party

```http
GET /api/v1/onboarding/records/{partyId}
```

`partyId` is a UUID. Returns the `OnboardingRecordDto`, or `404` if no record exists for that party.

```http
404 Not Found
{ "code": "NOT_FOUND", "message": "Onboarding record not found for party <id>" }
```

### Funnel KPI counts

```http
GET /api/v1/onboarding/funnel
```

Returns a map of every `FunnelStage` to its record count (stages with zero records are included with count `0`).

```http
200 OK
{
  "REGISTERED": 12,
  "KYC_OPEN": 45,
  "KYC_DOCUMENTS_REQUIRED": 8,
  "KYC_UNDER_REVIEW": 23,
  "SCA_PENDING": 6,
  "ACTIVE": 301,
  "BLOCKED": 4
}
```

## Error model

The OpenAPI `ApiError` schema is minimal: `{ code, message }`. The only error response currently emitted by the resource is the `404` above (`code=NOT_FOUND`). Authentication failures (`401`) are produced by the Quarkus OIDC layer when enabled. There is no rich problem+json envelope on this service yet (TBD — would align with the platform `ApiError` used by money-path services).

## Inbound events (ingestion contract)

The service is primarily an **event consumer**. It reads JSON string payloads from three topics and maps recognised `eventType` values to a sealed `OnboardingEvent`:

| Topic (channel) | `eventType` recognised | Projected to |
|---|---|---|
| `openbank.party.events` (`party-events-in`) | `PARTY_CREATED` | new record, stage `REGISTERED` |
| | `PARTY_STATUS_CHANGED`, `KYC_STATUS_UPDATED` | party status + re-derived stage |
| `openbank.kyc.events` (`kyc-events-in`) | `KYC_CASE_OPENED` | sets `kycCaseId`, kyc `OPEN` |
| | `KYC_CASE_STATUS_CHANGED`, `KYC_CASE_APPROVED`, `KYC_CASE_REJECTED` | kyc status + re-derived stage + `blockedReason` |
| `openbank.sca.events` (`sca-events-in`) | `DEVICE_ENROLLED` | `scaEnrolled=true`, `deviceCount++` |

Parsing is **lenient**: the consumer accepts `kycCaseId` or `caseId`, `newStatus` or `status`, defaults `occurredAt` to now if missing, and silently drops unknown event types or unparsable payloads (logged, then acked). This decouples the read-model from minor producer-schema drift.

## Versioning

- **API version in URL** (`/api/v1/...`). `openbank.api.version=1`, served by `openbank-libs`. Breaking changes ⇒ `/api/v2`.
- **API contract version** is `openapi.yaml:info.version` (currently `1.0.0`), the separate API-contract axis (ADR-0048) — independent of the released `version.txt`.
- **Event ingestion** is tolerant by design (additive producer changes are absorbed); a breaking producer change would be handled by the upstream topic owner, not here.
