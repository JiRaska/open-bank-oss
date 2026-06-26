# API & contracts

## Base

- **Production base:** `http://openbank-balance-service:8103/api/v1`
- **OpenAPI:** [`/q/openapi`](http://localhost:8103/q/openapi)

## Authentication

Bearer token (Keycloak realm `openbank`):

| Role | Rights |
|---|---|
| `ROLE_VIEWER` | GET only |
| `ROLE_OPERATOR` | GET + holds + capture + release |
| `ROLE_COMPLIANCE` | + set arranged overdraft |
| `ROLE_SERVICE_*` | service-to-service calls (transaction-service, card-issuance-service) |

## Endpoints

### Read balance

```http
GET /api/v1/balances/{accountId}
```

```http
200 OK
{
  "accountId": "acc-7f3e2a1b",
  "currency": "EUR",
  "booked":   "1234.56",
  "available":"1184.56",
  "reserved":  "50.00",
  "pending":    "0.00",
  "arrangedOverdraftLimit": "1000.00",
  "updatedAt": "2026-05-30T14:23:01Z",
  "version": 47
}
```

### Create hold

```http
POST /api/v1/balances/{accountId}/holds
Idempotency-Key: <uuid>

{
  "currency": "EUR",
  "amount": "50.00",
  "reason": "CARD_AUTHORIZATION",
  "referenceId": "auth-2026-05-30-1234",
  "expiresAt": "2026-05-30T20:00:00Z"
}
```

```http
201 Created
{
  "holdId": "hold-…",
  "balance": { …snapshot after the hold… }
}
```

**Reasons:** `CARD_AUTHORIZATION`, `PAYMENT_RESERVE`, `MANUAL_HOLD`, `COMPLIANCE_HOLD`.

### Capture hold

```http
POST /api/v1/balances/holds/{holdId}/capture
Idempotency-Key: <uuid>

{ "actualAmount": "47.30" }   // may be ≤ the original hold
```

### Release hold

```http
DELETE /api/v1/balances/holds/{holdId}
```

### Set arranged overdraft

```http
PATCH /api/v1/balances/{accountId}/overdraft
{ "currency": "EUR", "arrangedOverdraftLimit": "2000.00" }
```

## Error model (unified `openbank-libs.api.ApiError`)

| HTTP | code | When |
|---|---|---|
| 400 | `validation-failed` | DTO check (negative amount, missing currency) |
| 404 | `balance-not-found` | `(accountId,currency)` does not exist |
| 404 | `hold-not-found` | holdId does not exist |
| 409 | `idempotency-key-mismatch` | replay with a different body |
| 409 | `optimistic-lock-conflict` | concurrent update, client should retry |
| 422 | `insufficient-funds` | debit exceeded `available + arranged_overdraft` |
| 422 | `hold-already-captured` | cannot capture twice |
| 500 | `internal-error` | with correlationId |

## Events (`openbank.balance.events`)

| Event | Trigger | Key fields |
|---|---|---|
| `balance.opened.v1` | initialise for new account | accountId, currency, openedAt |
| `balance.updated.v1` | every change (booked/reserved/pending) | accountId, currency, deltas, version |
| `balance.hold.created.v1` | POST /holds | holdId, accountId, amount, reason, expiresAt |
| `balance.hold.captured.v1` | POST /capture | holdId, actualAmount |
| `balance.hold.released.v1` | DELETE /holds | holdId, reason (manual/expired) |
| `balance.low.v1` | `available < threshold` (per-account config) | accountId, currency, available, threshold |
| `balance.overdraft.changed.v1` | PATCH /overdraft | accountId, currency, oldLimit, newLimit |

## Backward compatibility

- API `/api/v1/...`. v2 runs in parallel for 6 months.
- Event version in topic name + per-event `v1` suffix in the type.
- OpenAPI diff in CI prevents breaking changes without a bump.
