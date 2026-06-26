# API & contracts

## Base

- **Production base:** `http://openbank-balance-service:8103/api/v1`
- **OpenAPI:** [`/q/openapi`](http://localhost:8103/q/openapi)

## Autentizace

Bearer token (Keycloak realm `openbank`):

| Role | Práva |
|---|---|
| `ROLE_VIEWER` | GET only |
| `ROLE_OPERATOR` | GET + holds + capture + release |
| `ROLE_COMPLIANCE` | + nastavení arranged overdraft |
| `ROLE_SERVICE_*` | servisní volání (transaction-service, card-issuance-service) |

## Endpointy

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

### Vytvoř hold

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
  "balance": { …snapshot po holdu… }
}
```

**Reasons:** `CARD_AUTHORIZATION`, `PAYMENT_RESERVE`, `MANUAL_HOLD`, `COMPLIANCE_HOLD`.

### Capture hold

```http
POST /api/v1/balances/holds/{holdId}/capture
Idempotency-Key: <uuid>

{ "actualAmount": "47.30" }   // může být ≤ původní hold
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

## Error model (jednotný `openbank-libs.api.ApiError`)

| HTTP | code | Kdy |
|---|---|---|
| 400 | `validation-failed` | DTO check (záporné amount, missing currency) |
| 404 | `balance-not-found` | `(accountId,currency)` neexistuje |
| 404 | `hold-not-found` | holdId neexistuje |
| 409 | `idempotency-key-mismatch` | replay s jiným body |
| 409 | `optimistic-lock-conflict` | paralelní update, klient má retry |
| 422 | `insufficient-funds` | debit překročil `available + arranged_overdraft` |
| 422 | `hold-already-captured` | nemůžeš capture dvakrát |
| 500 | `internal-error` | s correlationId |

## Eventy (`openbank.balance.events`)

| Event | Trigger | Klíčové fieldy |
|---|---|---|
| `balance.opened.v1` | inicializace pro nový účet | accountId, currency, openedAt |
| `balance.updated.v1` | každá změna (booked/reserved/pending) | accountId, currency, deltas, version |
| `balance.hold.created.v1` | POST /holds | holdId, accountId, amount, reason, expiresAt |
| `balance.hold.captured.v1` | POST /capture | holdId, actualAmount |
| `balance.hold.released.v1` | DELETE /holds | holdId, reason (manual/expired) |
| `balance.low.v1` | `available < threshold` (per účet config) | accountId, currency, available, threshold |
| `balance.overdraft.changed.v1` | PATCH /overdraft | accountId, currency, oldLimit, newLimit |

## Backward compatibility

- API `/api/v1/...`. v2 = paralelní 6 měsíců.
- Event verze v topic name + per-event `v1` suffix v type.
- OpenAPI diff v CI bránící breaking změnám bez bumpu.
