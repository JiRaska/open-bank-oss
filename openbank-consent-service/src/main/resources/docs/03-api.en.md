# API

All endpoints are under `/api/v1/consents`. The API contract major version (`1`) maps to the URL prefix `/api/v1` (`openbank.api.version=1`, ADR 0048). Content type is `application/json`.

> **Contract status:** an `openapi.yaml` ships with the service but is currently **partially stale** relative to the implemented resource (it predates the SCA-gated lifecycle — e.g. it shows `expiresAt`/`accountIds` and a `PENDING` status, whereas the code uses `validTo`/`accountIbans` and `PENDING_SCA`, and exposes `/activate` and `/reject`). The descriptions below are **derived from the actual `ConsentResource.kt`** and are authoritative; reconciling `openapi.yaml` to this is a tracked follow-up.

## Endpoints

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/api/v1/consents` | Create a consent (status `PENDING_SCA`) | `201 Created` + `Location` |
| `GET` | `/api/v1/consents/{id}` | Get consent by id | `200` |
| `GET` | `/api/v1/consents/party/{partyId}` | List consents granted by a party | `200` (array) |
| `GET` | `/api/v1/consents/grantee/{granteeId}` | List consents held by a grantee | `200` (array) |
| `POST` | `/api/v1/consents/{id}/activate?scaSessionId={uuid}` | Activate after SCA → emits `ConsentGranted` | `200` |
| `POST` | `/api/v1/consents/{id}/reject?reason={text}` | Reject → emits `ConsentRejected` | `200` |
| `DELETE` | `/api/v1/consents/{id}?partyId={uuid}` | Revoke → emits `ConsentRevoked` | `200` |
| `POST` | `/api/v1/consents/{id}/validate` | Validate access (scope/account/grantee) | `200` |

## Create consent

`POST /api/v1/consents`

```json
{
  "partyId": "0e1f…",
  "granteeId": "PSDCZ-CNB-12345",
  "granteeType": "TPP",
  "granteeName": "Acme Aggregator a.s.",
  "scopes": ["ACCOUNTS_READ", "TRANSACTIONS_READ"],
  "accountIbans": ["CZ6508000000192000145399"],
  "validTo": "2026-09-01T00:00:00Z",
  "redirectUri": "https://tpp.example/callback",
  "tppTransactionId": "tpp-ref-abc-123"
}
```

- `accountIbans` may be `null` ⇒ consent covers **all** of the party's accounts.
- `validTo` is **capped server-side**: AIS scopes ⇒ max `now + 90 days`; otherwise `now + 365 days`.
- Returns the created `Consent` with status `PENDING_SCA`. The consent is not usable until activated.

### Idempotency

Consent creation is idempotent. The key is derived as `consent:create:{granteeId}:{partyId}:{requestId}` where `requestId` is `tppTransactionId` if present, otherwise the `X-Request-ID` header. On a replay the cached `201` body is returned with header `X-Idempotency-Replayed: true`. Keys are stored in Redis with a 24 h TTL (`openbank.consent.idempotency-ttl-seconds=86400`). No idempotency key ⇒ no replay protection (each call creates a new consent).

## Validate consent

`POST /api/v1/consents/{id}/validate`

```json
{ "granteeId": "PSDCZ-CNB-12345", "requiredScope": "TRANSACTIONS_READ", "accountIban": "CZ65…" }
```

Response:

```json
{ "valid": true, "reason": null, "code": null }
```

Validation fails (`valid:false`) with one of the machine-readable `code`s:

| `code` | Meaning |
|---|---|
| `CONSENT_NOT_FOUND` | no consent with that id |
| `CONSENT_GRANTEE_MISMATCH` | consent belongs to a different grantee |
| `CONSENT_NOT_ACTIVE` | not `ACTIVE`, or past `validTo` |
| `CONSENT_SCOPE_MISSING` | required scope not granted |
| `CONSENT_ACCOUNT_NOT_COVERED` | the IBAN is outside the consent's account list |

Validation always returns HTTP `200`; the boolean `valid` carries the decision.

## Error model

Mutations and lookups return the shared `ApiError` envelope from `openbank-libs` (`{ traceId, status, code, message }`). Mapping from `ExceptionMappers.kt`:

| HTTP | `code` | When |
|---|---|---|
| `404` | `NOT_FOUND` | consent id unknown |
| `403` | `FORBIDDEN` | revoke with a `partyId` that does not own the consent |
| `409` | `CONFLICT` | activating an already-`ACTIVE` consent |
| `422` | `VALIDATION_ERROR` | SCA challenge not found / mismatched (`partyId`/purpose) / not `COMPLETED` |
| `503` | `SERVICE_UNAVAILABLE` | SCA service unreachable (after retries/circuit breaker) |

## Authentication & authorization

- **AuthN:** Keycloak OIDC, RS256 bearer JWT (`openbank-services` client). Disabled in `dev`/`test` profiles only.
- **AuthZ:** OPA sidecar via the libs `@Authorize` interceptor (ADR 0034). Currently `authz.enforce=false` (advisory) by default; `DELETE` (revoke) carries `@Authorize(action = "consent.revoke", resource = "#id")`. Ownership for revoke is additionally enforced in the domain (the `partyId` query param must match the consent's owner ⇒ `403` otherwise).

## Versioning

Single contract major `v1`. `X-API-Version` / `X-Service-Version` response headers and `/api/v1/info` are served by `openbank-libs`. There are no deprecated paths (`api_deprecation.deprecated_paths: []`).
