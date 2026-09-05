# API & contracts

The REST contract is formalized in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version: 1.1.0`). The URL major version (`/api/v1`) is the API-contract axis (ADR-0048) and is independent of the service release version (`version.txt`).

## Base path

- **In-cluster base:** `http://openbank-domestic-payment:8116/api/v1`
- **OpenAPI spec:** `/q/openapi`
- **Swagger UI:** `/api/docs` (`quarkus.swagger-ui.path`, always-included)

## Authentication

All endpoints require a **Keycloak Bearer token** (realm `openbank`, RS256 JWT). Per-endpoint roles:

| Endpoint | Roles allowed |
|---|---|
| `POST /domestic-payments` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /domestic-payments/{paymentId}` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` |
| `GET /domestic-payments` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS`, `ROLE_API` |
| `PATCH /domestic-payments/{paymentId}/status` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS` + `@Authorize(action="domesticPayment.transitionStatus")` (OPA, ADR-0034) |

OIDC is disabled only in `%dev` and `%test` profiles.

## Idempotence

`POST /domestic-payments` **requires** the header:

```
Idempotency-Key: <client-generated-unique-key>
```

Rules:
- A blank or longer-than-128-character key is rejected (`400`).
- Postgres atomically binds the key to a SHA-256 of the normalized command and authenticated actor scope, together with the payment and its outbox event.
- An exact replay returns the durable payment with `X-Idempotency-Replayed: true`; a changed request or unverifiable legacy row returns `409 IDEMPOTENCY_KEY_REUSED` (`application/problem+json`). Redis is not a create-payment authority. For an unverifiable legacy row, do not retry under a new key: the first attempt may have committed, so resolve it through payment-status lookup/operator reconciliation.

## Key endpoints

### Create a domestic payment

```http
POST /api/v1/domestic-payments
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "debtorAccountId": "f0c1...-uuid",
  "debtorAccountNumber": "19-2000145399",
  "debtorBankCode": "0800",
  "debtorName": "Jan Novák",
  "creditorAccountNumber": "123456789",
  "creditorBankCode": "0100",
  "creditorName": "ČEZ a.s.",
  "amount": 1500.00,
  "currency": "CZK",
  "variableSymbol": "1234567890",
  "specificSymbol": null,
  "constantSymbol": "0308",
  "messageForPayee": "Invoice 2026-01",
  "priority": "STANDARD",
  "transferScope": "INTERNAL_CLIENT",
  "technicalAccountCode": null,
  "statementLabel": null,
  "endToEndId": null
}
```

```http
201 Created
Location: /api/v1/domestic-payments/{paymentId}
Content-Type: application/json

{
  "id": "…uuid…",
  "status": "VALIDATED",      // or RECEIVED (held for review / screening down) / REJECTED (sanctions hit)
  "debtorAccountId": "…",
  "creditorAccountNumber": "123456789",
  "creditorBankCode": "0100",
  "amount": 1500.00,
  "currency": "CZK",
  "endToEndId": "DOMS1716...",
  "rejectReason": null,
  "createdAt": "2026-06-09T10:42:13Z"
}
```

**Notes:**
- The returned `status` reflects the synchronous screening outcome (see [02 — Architecture](./02-architecture.md)): `VALIDATED` (clear), `RECEIVED` (held for review or screening unavailable), or `REJECTED` with `rejectReason=SANCTIONS_HIT`.
- `endToEndId` is generated if not supplied (`DOM<priority-initial><epoch-millis><rand>`).
- `transferScope=TECHNICAL_ACCOUNT` requires `technicalAccountCode`.
- A `domestic.payment.created` outbox event is written in the same transaction; a `domestic.payment.status-changed` event follows if screening transitions the payment.

### Get a payment

```http
GET /api/v1/domestic-payments/{paymentId}
→ 200 (DomesticPaymentResponse) | 404 (ApiError NOT_FOUND)
```

### List payments

```http
GET /api/v1/domestic-payments?status=VALIDATED&debtorAccountId={uuid}&limit=50&offset=0
→ 200 [DomesticPaymentResponse]
```

`limit` is coerced into `1..200`; `offset` is coerced to `>= 0`.

### Transition status

```http
PATCH /api/v1/domestic-payments/{paymentId}/status
{
  "targetStatus": "SENT_TO_CLEARING",
  "rejectReason": null,          // required when targetStatus = REJECTED
  "rejectDetail": null
}
→ 200 (updated DomesticPaymentResponse) | 404 | 409 (illegal transition)
```

State machine (`DomesticPayment.canTransitionTo`):

```
RECEIVED         → VALIDATED | REJECTED | CANCELLED
VALIDATED        → SENT_TO_CLEARING | REJECTED | CANCELLED
SENT_TO_CLEARING → SETTLED | RETURNED | REJECTED
SETTLED / REJECTED / RETURNED / CANCELLED → (terminal)
```

`REJECTED` requires a `rejectReason` (e.g. `SANCTIONS_HIT`, `AML_HOLD`, `INSUFFICIENT_FUNDS`, `AMOUNT_LIMIT_EXCEEDED`, …). `submittedAt` is stamped on first non-`RECEIVED` transition; `settledAt` on `SETTLED`/`RETURNED`/`CANCELLED`.

## Error model

Errors use `openbank-libs` `ApiError` (`{ correlationId, status, code, message }`):

| HTTP | code | When |
|---|---|---|
| 400 | (validation) | missing `Idempotency-Key`, malformed body / enum |
| 401 | unauthorized | missing / invalid token |
| 403 | forbidden | role missing for the endpoint (or OPA deny in enforce mode) |
| 404 | `NOT_FOUND` | payment id does not exist (`DomesticPaymentNotFoundException`) |
| 409 | `CONFLICT` | illegal status transition (`InvalidDomesticPaymentStateTransitionException`) |

## Events

Topic: `openbank.domestic.payment.events` (Kafka, JSON, channel `events-out`).

| Event type | Trigger | Payload (key fields) |
|---|---|---|
| `domestic.payment.created` | `POST /domestic-payments` | paymentId, idempotencyKey, status, debtorAccountId, debtor/creditor account+bank, amount, currency, priority, endToEndId, occurredAt |
| `domestic.payment.status-changed` | any persisted transition | paymentId, previousStatus, newStatus, rejectReason, rejectDetail, occurredAt |

Events are **append-only**; corrections are made by a new status-changed event, not by rewriting.

## Backward compatibility

- **API version in URL** (`/api/v1/...`). A breaking change goes to `/api/v2/...`; classify the bump from the OpenAPI diff (`oasdiff`), not the commit type (ADR-0048).
- **Event evolution:** additive (new optional fields) only on the existing topic; a breaking change means a new topic.
- **OpenAPI diff** is gated in CI against `main`.
