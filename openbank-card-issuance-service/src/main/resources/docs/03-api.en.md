# API & contracts

The REST contract is described in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1, `info.version: 1.0.0`). The endpoints below are the authoritative set as implemented in `CardResource`; where the committed `openapi.yaml` and the code disagree, the code wins and the gap is flagged inline (the spec is not yet fully reconciled — see notes at the end).

## Base path

- **App base:** `http://openbank-card-issuance-service:8118/api/v1` (in-cluster)
- **Swagger UI (dev):** `/api/docs`
- **OpenAPI spec:** served by SmallRye OpenAPI

## Authentication

All endpoints require a **Keycloak Bearer token** (realm `openbank`). Operations are role-gated via `@RolesAllowed`:

| Endpoint | Roles |
|---|---|
| `GET` (all read endpoints) | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards` (issue) | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/activate` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/suspend` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/resume` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /cards/{id}/block` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE` |

## Idempotence

Card issue requires the header:

```
Idempotency-Key: <client-generated-uuid-v4>
```

The resource rejects a blank key. The key is persisted on the `cards` row (`idempotency_key`, `UNIQUE NOT NULL`); `CardService.issueCard` first calls `findByIdempotencyKey` and, on a hit, returns the existing card instead of issuing a new one — so a retry with the same key is replay-safe and never produces a duplicate card.

> Status-change endpoints (`activate`/`suspend`/`resume`/`block`) are naturally idempotent at the domain level: the state machine `require(...)` guards reject a transition from a state that is already terminal/incompatible.

## Endpoints

### Issue a card

```http
POST /api/v1/cards
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "partyId": "0f9d…",
  "accountId": "1a2b…",
  "productCode": "DEBIT_STANDARD",
  "cardType": "DEBIT",
  "network": "VISA",
  "cardholderName": "JAN NOVAK",
  "embossedName": "JAN NOVAK",
  "currency": "CZK",
  "dailyLimitMinorUnits": 500000,
  "monthlyLimitMinorUnits": 5000000,
  "deliveryAddress": "Václavské náměstí 1, Praha"
}
```

```http
201 Created
Location: /api/v1/cards/{id}

{
  "id": "…",
  "partyId": "…",
  "accountId": "…",
  "productCode": "DEBIT_STANDARD",
  "cardType": "DEBIT",
  "network": "VISA",
  "maskedPan": "**** **** **** 1234",
  "cardholderName": "JAN NOVAK",
  "embossedName": "JAN NOVAK",
  "expiryDate": "2030-06-09",
  "status": "PENDING",
  "dailyLimitMinorUnits": 500000,
  "monthlyLimitMinorUnits": 5000000,
  "currency": "CZK",
  "deliveryAddress": "…",
  "activatedAt": null,
  "blockedAt": null,
  "blockedReason": null,
  "createdAt": "…",
  "updatedAt": "…"
}
```

Defaults: `dailyLimitMinorUnits=500000`, `monthlyLimitMinorUnits=5000000` when omitted; `expiryDate` is set to issue date + 4 years; the masked PAN is generated server-side. **Side-effect:** a `card_outbox` row carrying `card.issued.v1`.

### Lifecycle transitions

| Call | From → To | Body | Header |
|---|---|---|---|
| `POST /cards/{id}/activate` | PENDING → ACTIVE | — | `X-Operator-Id` |
| `POST /cards/{id}/suspend` | ACTIVE → SUSPENDED | — | `X-Operator-Id` |
| `POST /cards/{id}/resume` | SUSPENDED → ACTIVE | — | `X-Operator-Id` |
| `POST /cards/{id}/block` | {ACTIVE, SUSPENDED} → BLOCKED | `{ "reason": "LOST" }` | `X-Operator-Id` |

`X-Operator-Id` is recorded as `changedBy` on the emitted `card.status_changed.v1` event. A transition that violates the state machine raises a precondition failure (e.g. activating a non-PENDING card, blocking with a blank reason).

### Reads

| Call | Returns |
|---|---|
| `GET /api/v1/cards` | all cards |
| `GET /api/v1/cards/{id}` | one card, or `404 {"error":"Card not found"}` |
| `GET /api/v1/cards/account/{accountId}` | cards for an account |
| `GET /api/v1/cards/party/{partyId}` | cards for a party |

## Error model

`GET /cards/{id}` returns `404` with a simple `{"error":"Card not found"}` body. Other failures surface as standard Quarkus/RESTEasy responses:

| HTTP | When |
|---|---|
| 400 | malformed body, blank `Idempotency-Key`, illegal state transition (`require`/`IllegalArgumentException`) |
| 401 | missing / invalid token |
| 403 | role missing for the endpoint |
| 404 | card id does not exist |
| 500 | unexpected error |

> A unified problem+json error envelope is not yet wired in this service; this is a hardening follow-up.

## Events

Topic: `openbank.cards.events`.

| Event type | Trigger | Payload (key fields) |
|---|---|---|
| `card.issued.v1` | `POST /cards` | cardId, partyId, accountId, cardType, network, maskedPan, occurredAt |
| `card.status_changed.v1` | activate / suspend / resume / block | cardId, previousStatus, newStatus, reason, changedBy, occurredAt |

Each Kafka record carries `ce-id` (= event id), `idempotency-key` (= event id) and `ce-type` (event type) headers; the partition key is the card id (see [02 — Architecture](./02-architecture.md)).

## Backward compatibility

- **API version in URL** (`/api/v1/...`). Breaking changes ⇒ `/api/v2/...` (ADR-0048: the `openapi.yaml:info.version` major == `/api/v{N}`).
- **Event version in the type suffix** (`...v1`). Schema evolution: additive only; breaking changes get a new version suffix.
- CI `oasdiff` gates API changes against `main`.

## Known spec/code gaps (to reconcile)

- `openapi.yaml` lists the dev server on port `8115`; the service actually runs on **8118** (`application.yaml`).
- `openapi.yaml` `IssueCardRequest` omits `productCode`, `cardholderName`, `currency`, `dailyLimitMinorUnits`, `monthlyLimitMinorUnits` which the code requires/accepts, and models `deliveryAddress` as an object whereas the code uses a string.
- The `cardType` / `cardNetwork` enums in the spec are narrower than the domain (`VIRTUAL`, `AMEX`, `UNIONPAY`, `CANCELLED`, `PENDING` are missing).
- `GET /api/v1/cards` (list all) and the role/`X-Operator-Id` semantics are not fully described in the spec.
