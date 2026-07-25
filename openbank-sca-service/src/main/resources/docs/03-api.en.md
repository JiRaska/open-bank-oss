# API

REST contract under the base path `/api/v1/sca`. The URL major version (`v1`) maps to `openbank.api.version = "1"` (ADR-0048). `X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`.

> **Contract note:** an `openapi.yaml` is present in the service but is **stale** relative to the implementation (it lists a different dev port, different challenge-type/purpose enums, and slightly different request bodies). The authoritative contract below is taken from the actual `ScaResource` and domain code; the `openapi.yaml` should be regenerated to match (tracked as a follow-up). Where the two disagree, the code wins.

## Endpoints

| Method | Path | Auth (`@Authorize`) | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/sca/challenges` | — (authenticated) | Initiate an SCA challenge |
| `POST` | `/api/v1/sca/challenges/{id}/verify` | `scaChallenge.verify` | Verify (OTP) or poll decoupled status |
| `GET` | `/api/v1/sca/challenges/{id}` | — (authenticated) | Get challenge status |
| `POST` | `/api/v1/sca/parties/{partyId}/devices` | `device.enroll` | Enrol a device credential |
| `GET` | `/api/v1/sca/parties/{partyId}/devices` | `device.list` | List a party's enrolled devices |
| `POST` | `/api/v1/sca/challenges/{id}/decision` | `scaChallenge.decide` | Record out-of-band device approval/denial |

`@Authorize` is evaluated against the OPA sidecar (ADR-0034, advisory by default — `authz.enforce=false`). The `parties/{partyId}/devices` endpoints additionally enforce ownership in code: a principal whose name is a UUID (a party, not an operator) may only act on its **own** `partyId`; `ROLE_OPERATOR` / `ROLE_ADMIN` may act on behalf of a party.

## Initiate — `POST /api/v1/sca/challenges`

Request:
```json
{
  "partyId": "uuid",
  "purpose": "PAYMENT_INITIATION | CONSENT_GRANT | LOGIN | AGENT_ACTION | SENSITIVE_DATA_ACCESS",
  "preferredMethod": "PUSH_NOTIFICATION | TOTP | BIOMETRIC",
  "dynamicLinkingData": {
    "amount": "100.00", "currency": "EUR",
    "creditorIban": "…", "creditorName": "…", "reference": "…"
  },
  "redirectUrl": "https://…"
}
```
`preferredMethod`, `dynamicLinkingData`, `redirectUrl` are optional (method defaults to `PUSH_NOTIFICATION`). Response `201` with the challenge body (below). TTL is 300 s.

### Idempotency
Send `Idempotency-Key` (preferred) or `X-Request-ID`. A replay returns the cached `201` body with header `X-Idempotency-Replayed: true`. The REST cache key is `sca:initiate:{partyId}:{key}` (Redis, 300 s). Independently, the use case derives an idempotency key from the full command (party + purpose + method + dynamic-linking fields + redirectUrl) and returns the same challenge for identical re-initiations.

## Verify — `POST /api/v1/sca/challenges/{id}/verify`

Request: `{ "partyId": "uuid", "otp": "123456" }` (`otp` required for TOTP).
- **OTP methods**: a correct OTP ⇒ `COMPLETED`; a wrong OTP increments `attemptCount` and, once `maxAttempts` (3) is reached, the challenge becomes `FAILED` and the call returns `401`.
- **Decoupled methods (PUSH/BIOMETRIC)**: returns the current challenge. `COMPLETED` only if the enrolled device already posted an APPROVED decision; otherwise it stays `PENDING` (no attempt consumed) — never auto-approved (ADR-0021).

## Get — `GET /api/v1/sca/challenges/{id}`

Response `200` with the challenge body, `404` if unknown.

## Enrol device — `POST /api/v1/sca/parties/{partyId}/devices`

Request:
```json
{ "credentialId": "stable-credential-id",
  "publicKey": "<base64 X.509 SubjectPublicKeyInfo>",
  "algorithm": "ES256 | ED25519" }
```
Response `201` with the enrolled-device body. Emits a `DEVICE_ENROLLED` outbox event.

## Record decision — `POST /api/v1/sca/challenges/{id}/decision`

Authenticated as the enrolled device/party (a *different* principal from the verify caller).
```json
{ "credentialId": "…", "decision": "APPROVED | DENIED",
  "signature": "<base64 signature over the dynamic-linking payload>" }
```
The service verifies the signature against the device public key over `id | decision | amount | currency | creditorIban | reference` (RTS Art. 5 dynamic linking) before recording it. A decision is **write-once** (a second call is rejected so a DENIED cannot be overwritten with APPROVED).

## Response bodies

**ScaChallengeResponse**: `id`, `partyId`, `purpose`, `method`, `status` (PENDING/COMPLETED/FAILED/EXPIRED/CANCELLED), `expiresAt`, `completedAt`, `attemptCount`, `maxAttempts`.

**EnrolledDeviceResponse**: `id`, `partyId`, `credentialId`, `algorithm`, `enrolledAt`.

## Error model

Errors use the shared `ApiError` (libs): `{ traceId, status, code, message }`.

| Domain condition | HTTP | `code` |
|---|---|---|
| Challenge / device not found | `404` | `NOT_FOUND` |
| Challenge expired | `422` | `VALIDATION_ERROR` |
| Max attempts exceeded | `429` | `VALIDATION_ERROR` |
| OTP verification failed (terminal) | `401` | `UNAUTHORIZED` |
| Challenge not awaiting a decision / decision exists | `409` | `VALIDATION_ERROR` |
| Device does not belong to challenge party | `403` | `FORBIDDEN` |
| Invalid device assertion (bad signature) | `401` | `UNAUTHORIZED` |
| Caller acting on another party's devices | `403` | `FORBIDDEN` |

## Versioning

API changes follow the OpenAPI-diff (`oasdiff`) classification, independent of the release version (ADR-0048). The release version lives in `version.txt` (currently `0.4.0`); the API-contract version lives in `openapi.yaml:info.version` and must be brought back in sync with the code as part of the contract-regeneration follow-up.
