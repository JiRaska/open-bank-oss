# API & contracts

## Base path

- **Production base:** `http://openbank-account-service:8100/api/v1` (in-cluster)
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8100/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8100/q/swagger-ui)

## Authentication

All endpoints require a **Keycloak Bearer token** with realm `openbank`. Mutating operations are additionally role-gated:

| Role | Rights |
|---|---|
| `ROLE_VIEWER` | GET only |
| `ROLE_OPERATOR` | GET + open / freeze / unfreeze / close |
| `ROLE_COMPLIANCE` | GET + freeze / unfreeze (court orders) |
| `ROLE_ADMIN` | everything |

## Idempotence

All **POST / PUT / PATCH** require the header:

```
Idempotency-Key: <client-generated-uuid-v4>
```

Rules:
- Client generates a UUID v4 per logical request (NOT per HTTP retry).
- The key+endpoint is cached for 24 h in Redis. Same key + same body → returns the cached response (replay-safe).
- Same key + different body → **409 Conflict** with `code=idempotency-key-mismatch`.

## Key endpoints

### Open account

```http
POST /api/v1/accounts
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b

{
  "ownerPartyId": "party-abc123",
  "type": "CURRENT",
  "currency": "EUR",
  "initialBalance": { "amount": "1000.00", "currency": "EUR" }
}
```

```http
201 Created
Location: /api/v1/accounts/acc-7f3e2a1b
Content-Type: application/json

{
  "id": "acc-7f3e2a1b",
  "iban": "CZ6508000000192000145399",
  "ownerPartyId": "party-abc123",
  "type": "CURRENT",
  "currency": "EUR",
  "status": "ACTIVE",
  "openedAt": "2026-05-29T10:42:13Z"
}
```

**Side-effects:**
- `account_outbox` row with the `AccountOpened` event (dispatcher publishes to Kafka in < 1 s).
- `audit-service` records the change (event consumer).

### Freeze account

```http
POST /api/v1/accounts/{id}/freeze
Idempotency-Key: ...

{
  "reason": "AML_HOLD",
  "reference": "case-2026-1234",
  "expiresAt": "2026-06-15T00:00:00Z"
}
```

Reasons: `AML_HOLD`, `COURT_ORDER`, `FRAUD_INVESTIGATION`, `CUSTOMER_REQUEST`.

### Authorizations

```http
POST /api/v1/accounts/{id}/authorizations
{
  "partyId": "party-xyz",
  "role": "SIGNATORY",
  "scope": ["VIEW", "TRANSACT"]
}
```

## Error model

Unified via `openbank-libs.api.ApiError`:

```json
{
  "type": "https://openbank.example/errors/account-frozen",
  "title": "Account is frozen",
  "status": 422,
  "detail": "Account acc-7f3e2a1b is frozen (reason=AML_HOLD until 2026-06-15)",
  "instance": "/api/v1/accounts/acc-7f3e2a1b/close",
  "correlationId": "01HXXXX...",
  "code": "account-frozen"
}
```

| HTTP | code | When |
|---|---|---|
| 400 | `validation-failed` | DTO validation (Iban, Currency, …) |
| 401 | `unauthorized` | missing / invalid token |
| 403 | `forbidden` | role missing for the endpoint |
| 404 | `account-not-found` | ID does not exist |
| 409 | `idempotency-key-mismatch` | replay with a different body |
| 409 | `account-already-closed` | close has already happened |
| 422 | `account-frozen` | mutation on a frozen account |
| 422 | `insufficient-authorization` | not OWNER for this operation |
| 429 | `rate-limited` | per-token quota |
| 500 | `internal-error` | unexpected error (with correlationId for support) |

## Events

Topic: `openbank.account.events.v1` (CloudEvents binding, JSON).

| Event type | Trigger | Payload (key fields) |
|---|---|---|
| `account.opened.v1` | POST /accounts | id, iban, ownerPartyId, type, currency, openedAt |
| `account.frozen.v1` | POST .../freeze | id, reason, reference, frozenAt, expiresAt |
| `account.unfrozen.v1` | POST .../unfreeze | id, reason, unfrozenAt |
| `account.closed.v1` | POST .../close | id, reason, closedAt, finalBalance |
| `authorization.granted.v1` | POST .../authorizations | accountId, partyId, role, scope |
| `authorization.revoked.v1` | DELETE .../authorizations/{id} | accountId, partyId, revokedAt |

Events are **append-only**; corrections are made through a compensating event (e.g. `account.unfrozen.v1`), not by rewriting.

## Backward compatibility

- **API version in URL** (`/api/v1/...`). Breaking changes = `/api/v2/...`, v1 runs in parallel for 6 months.
- **Event version in topic name** (`...v1`). Schema evolution: only additive (adding optional fields), breaking = new topic.
- **OpenAPI diff** in CI against `main` — fail when a removed endpoint or a required-added field appears without a version bump.
