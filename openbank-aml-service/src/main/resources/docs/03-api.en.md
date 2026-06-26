# API & contracts

## Base path

- **In-cluster base:** `http://openbank-aml-service:8117/api/v1` (app port 8117)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8117/q/openapi)
- **Swagger UI:** [`/api/docs`](http://localhost:8117/api/docs) (`quarkus.swagger-ui.always-include=true`)
- **API version:** `v1` (`openbank.api.version=1`, URL prefix `/api/v1`)

> **Contract note:** the checked-in `openapi.yaml` is partly out of date relative to the implemented `AmlCaseResource` — it documents different field names (e.g. `triggerType`/`riskScore`/`decision`) and a different status enum (`SAR_FILED`, `NO_ACTION`, …). **This page documents the contract actually served by the code.** The OpenAPI document should be re-synced to the resource (tracked as a follow-up).

## Authentication & authorization

All endpoints require a **Keycloak Bearer token** (realm `openbank`, OIDC). Mutating operations are role-gated via `@RolesAllowed`:

| Endpoint | Required role(s) |
|---|---|
| `POST /api/v1/aml/cases` | `ROLE_OPERATOR`, `ROLE_ADMIN` or `ROLE_COMPLIANCE` |
| `GET /api/v1/aml/cases` / `GET .../{caseId}` | authenticated (no extra role) |
| `PUT /api/v1/aml/cases/{caseId}/decision` | `ROLE_OPERATOR`, `ROLE_ADMIN` or `ROLE_COMPLIANCE` |

The decision endpoint additionally carries `@Authorize(action = "amlCase.updateDecision", resource = "#caseId")` — an OPA policy check via the sidecar PDP (ADR-0034). Authz is **advisory** by default (`authz.enforce=false`): denies are logged at WARN and the request still proceeds; flip with `AUTHZ_ENFORCE=true`.

## Idempotence

`POST /api/v1/aml/cases` **requires** the header:

```
Idempotency-Key: <client-generated-key>
```

Rules:
- The header must be non-blank (otherwise the request is rejected).
- The key is cached in Redis. A replay with the same key returns the cached response with header `X-Idempotency-Replayed: true`.
- The key is also persisted as a unique `idempotency_key` column on the case row — a redelivered create reuses the existing case rather than creating a duplicate. (The onboarding consumer uses `"<partyId>:CUSTOMER_ONBOARDING"` as its key.)

## Endpoints

### Create AML case

```http
POST /api/v1/aml/cases
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "partyId": "f0a1...uuid",
  "accountId": null,
  "transactionId": null,
  "customerReference": "CUST-00042",
  "screeningType": "TRANSACTION_MONITORING",
  "riskLevel": "HIGH",
  "alertCode": "TXN_THRESHOLD",
  "alertDetail": "Aggregate > 15k EUR / 24h",
  "matchedEntity": null
}
```

```http
201 Created
Location: /api/v1/aml/cases/{caseId}
Content-Type: application/json

{
  "id": "...uuid",
  "idempotencyKey": "5e9f8b6a-...",
  "partyId": "f0a1...uuid",
  "accountId": null,
  "transactionId": null,
  "customerReference": "CUST-00042",
  "screeningType": "TRANSACTION_MONITORING",
  "riskLevel": "HIGH",
  "status": "UNDER_REVIEW",
  "alertCode": "TXN_THRESHOLD",
  "alertDetail": "Aggregate > 15k EUR / 24h",
  "matchedEntity": null,
  "decisionReason": null,
  "assignedAnalyst": null,
  "decidedBy": null,
  "screenedAt": "2026-06-09T10:42:13Z",
  "decidedAt": null,
  "createdAt": "2026-06-09T10:42:13Z",
  "updatedAt": "2026-06-09T10:42:13Z"
}
```

- `screeningType` ∈ `CUSTOMER_ONBOARDING | TRANSACTION_MONITORING | PERIODIC_REVIEW | MANUAL_INVESTIGATION`
- `riskLevel` ∈ `LOW | MEDIUM | HIGH | CRITICAL` — `HIGH`/`CRITICAL` open `UNDER_REVIEW`, `LOW`/`MEDIUM` open `OPEN`.
- **Side-effects:** an `aml_outbox` row with `aml.case.created.v1`; the dispatcher publishes to Kafka within seconds.

### Get AML case

```http
GET /api/v1/aml/cases/{caseId}
```
`200 OK` with the case body, or `404` if the id does not exist.

### List AML cases

```http
GET /api/v1/aml/cases?status=UNDER_REVIEW&partyId=...&screeningType=TRANSACTION_MONITORING&limit=50&offset=0
```
All query params optional. `limit` defaults to 50 (clamped to 1..200 server-side); `offset` defaults to 0. Returns a JSON array of case bodies.

### Update decision

```http
PUT /api/v1/aml/cases/{caseId}/decision
Content-Type: application/json

{
  "targetStatus": "CLEARED",
  "decisionReason": "No adverse match after review",
  "assignedAnalyst": "analyst-7",
  "decidedBy": "analyst-7"
}
```

- `targetStatus` must be a valid transition from the current status (see the state machine in [02 — Architecture](./02-architecture.md)).
- `decidedBy` is mandatory; `decisionReason` is mandatory when `targetStatus = BLOCKED`.
- **Side-effects:** the transition + an `aml.case.status_changed.v1` event commit atomically via the outbox.

## Error model

Unified via `openbank-libs` `ApiError` (`{ correlationId, status, code, message }`):

| HTTP | code | When |
|---|---|---|
| 400 | validation | missing `Idempotency-Key`, bad enum value, malformed body |
| 401 | unauthorized | missing / invalid token |
| 403 | forbidden | role missing, or OPA deny (when enforcing) |
| 404 | `not-found` | `AmlCaseNotFoundException` — case id does not exist |
| 409 | `conflict` | `InvalidAmlCaseStateTransitionException` — illegal state transition |
| 500 | internal-error | unexpected error (with correlationId for support) |

## Events

Outbound topic: `openbank.aml.events` (JSON; partition key = case aggregate id; headers `ce-id` / `idempotency-key` / `ce-type`).

| Event type | Trigger | Payload (key fields) |
|---|---|---|
| `aml.case.created.v1` | `POST /aml/cases` or onboarding consumer | caseId, idempotencyKey, partyId, accountId, transactionId, customerReference, screeningType, riskLevel, status, alertCode, matchedEntity, occurredAt |
| `aml.case.status_changed.v1` | `PUT .../decision` | caseId, partyId, previousStatus, newStatus, decisionReason, assignedAnalyst, decidedBy, occurredAt |

Inbound topic: `openbank.party.events` — consumed for `PARTY_CREATED` (group `openbank-aml-service`, `auto.offset.reset=earliest`).

Events are **append-only**; corrections are made through a follow-up transition event, not by rewriting.

## Backward compatibility

- **API version in URL** (`/api/v1/...`). Breaking changes ⇒ `/api/v2/...`. The two version axes (release `version.txt` vs `openapi.yaml:info.version`) are independent (ADR-0048).
- **Event version in the type suffix** (`...v1`). Schema evolution is additive (optional fields); breaking changes get a new version suffix.
- **OpenAPI diff** in CI against `main` — but note the current spec drift above must be reconciled first.
