# API & contracts

The REST contract is published as OpenAPI ([`src/main/resources/openapi.yaml`](../openapi.yaml), `info.version: 1.1.0`) and served at runtime. The URL major (`/api/v1`) is bound to `openbank.api.version = 1` (ADR-0048). The endpoint shapes below are grounded in `SepaPaymentResource` and the DTOs.

> **Contract note:** the checked-in `openapi.yaml` is slightly behind the code in a few places (it lists status enum values `PENDING/RECALLED` and a `code/message` error body, and a stale local server port `8102`). The authoritative behaviour is what `SepaPaymentResource`, `SepaPaymentDtos` and `ExceptionMappers` implement, described here. Reconciling the spec is tracked as a follow-up.

## Base path

- **App port:** `8115`; **Management port:** `8085` (root-path `/q`)
- **Base path:** `/api/v1/sepa-payments`
- **OpenAPI spec:** `/q/openapi` · **Swagger UI:** `/api/docs` (configured `quarkus.swagger-ui.path`)

## Authentication & authorization

All endpoints require a **Keycloak Bearer token** (realm `openbank`). Roles are gated per endpoint:

| Endpoint | Roles allowed |
|---|---|
| `POST /sepa-payments` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /sepa-payments/{id}` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /sepa-payments` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS`, `ROLE_API` |
| `PATCH /sepa-payments/{id}/status` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |

The status transition is additionally guarded by OPA via `@Authorize(action = "sepaPayment.transitionStatus", resource = "#paymentId")` — advisory by default, enforced when `AUTHZ_ENFORCE=true` (ADR-0034).

## Idempotence

`POST /sepa-payments` **requires** the header:

```
Idempotency-Key: <client-generated-uuid>
```

Rules:
- A blank key is rejected (`400`).
- On a cache hit the previously stored response is replayed with header `X-Idempotency-Replayed: true` (Redis-backed `IdempotencyStore`).
- The use-case additionally deduplicates by `idempotency_key` (UNIQUE in the DB) and returns the existing payment, so a retry never produces a duplicate transfer.

## Endpoints

### Create a SEPA payment

```http
POST /api/v1/sepa-payments
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "type": "SCT",
  "debtorAccountId": "11111111-1111-1111-1111-111111111111",
  "debtorIban": "CZ6508000000192000145399",
  "debtorName": "Alice Debtor",
  "creditorIban": "DE89370400440532013000",
  "creditorName": "Bob Creditor",
  "creditorBic": "COBADEFFXXX",
  "amount": 1250.00,
  "currency": "EUR",
  "remittanceInfo": "Invoice 2026-0042",
  "endToEndId": "E2E-2026-0042"
}
```

```http
201 Created
Location: /api/v1/sepa-payments/<paymentId>

{
  "id": "…",
  "type": "SCT",
  "status": "VALIDATED",            // or RECEIVED (held) / REJECTED (sanctions hit)
  "debtorAccountId": "…",
  "creditorIban": "DE89370400440532013000",
  "amount": 1250.00,
  "currency": "EUR",
  "endToEndId": "E2E-2026-0042",
  "rejectReason": null,
  "createdAt": "2026-06-09T10:42:13Z",
  "updatedAt": "2026-06-09T10:42:13Z"
}
```

**Final status reflects the synchronous screening verdict** (ADR-0032): `VALIDATED` (clear), `RECEIVED` (held for review or screening outage), or `REJECTED` with `rejectReason=SANCTIONS_HIT`. `endToEndId` is generated if omitted.

**Side-effects:** a `sepa_payment_outbox` row with `sepa.payment.created` (and `sepa.payment.status-changed` when the verdict changes the status); an AML case opened in `aml-service` on hit/hold.

### Get a payment

```http
GET /api/v1/sepa-payments/{paymentId}
→ 200 OK SepaPaymentResponse  |  404 if not found
```

### List payments

```http
GET /api/v1/sepa-payments?status=VALIDATED&debtorAccountId=…&limit=50&offset=0
→ 200 OK [SepaPaymentResponse]
```

`limit` is clamped to 1..200, `offset` to ≥ 0. `status` accepts the domain values below.

### Transition status

```http
PATCH /api/v1/sepa-payments/{paymentId}/status
{ "targetStatus": "PROCESSING", "rejectReason": null, "rejectDetail": null }
→ 200 OK SepaPaymentResponse  |  409 on an invalid transition
```

`REJECTED` requires a `rejectReason`.

## Status model

```
RECEIVED ──► VALIDATED ──► PROCESSING ──► COMPLETED
   │             │              │
   ├─► REJECTED  ├─► REJECTED   ├─► REJECTED
   ├─► CANCELLED ├─► CANCELLED  └─► RETURNED
   │
(COMPLETED / REJECTED / RETURNED / CANCELLED are terminal)
```

`SepaRejectReason` ∈ `INSUFFICIENT_FUNDS · INVALID_IBAN · ACCOUNT_CLOSED · ACCOUNT_FROZEN · INVALID_BIC · AMOUNT_LIMIT_EXCEEDED · AML_HOLD · SANCTIONS_HIT · TECHNICAL_ERROR`.

## Error model

Unified via `openbank-libs` `ApiError` (`correlationId`, `status`, `code`, `message`):

| HTTP | code | When |
|---|---|---|
| 400 | (bad request) | blank `Idempotency-Key`, invalid enum value |
| 401 | unauthorized | missing / invalid token |
| 403 | forbidden | role missing, or OPA deny (enforce mode) |
| 404 | `NOT_FOUND` | payment id does not exist (`SepaPaymentNotFoundMapper`) |
| 409 | `CONFLICT` | invalid status transition (`InvalidSepaPaymentStateTransitionMapper`) |

## Events

Topic: `openbank.sepa.payment.events` (JSON, drained from the outbox).

| Event type | Trigger | Payload (key fields) |
|---|---|---|
| `sepa.payment.created` | `POST /sepa-payments` | paymentId, idempotencyKey, type, status, debtorAccountId, debtorIban, creditorIban, amount, currency, endToEndId, occurredAt |
| `sepa.payment.status-changed` | screening verdict or `PATCH …/status` | paymentId, previousStatus, newStatus, rejectReason, rejectDetail, occurredAt |

Events are append-only; corrections are made via a compensating status transition, not by rewriting.

## Backward compatibility

- **API version in URL** (`/api/v1/...`); a breaking change ⇒ `/api/v2/...` (ADR-0048, classified from the `oasdiff`).
- **Event evolution** is additive (optional fields only); a breaking change ⇒ a new topic.
