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

## Amount and currency at the boundary (#11604)

Every money-carrying input is built into a kernel `Money` (`Money.parseInbound`) before anything
else happens — before the hold's `(accountId, currency, referenceId)` replay lookup (ADR-0287), the
credit/debit `referenceId` movement marker (V8), any write, and any outbox event. Applies to
`POST /holds`, `/credit`, `/debit` (`amount` + `currency`), `/initialize` (`initialAmount`,
`arrangedOverdraftLimit` + `currency`) and `PUT /{currency}/overdraft-limit` (`arrangedOverdraftLimit`).

| Input | Answer |
|---|---|
| more decimals than the currency allows (`10.005 EUR`, `1000.5 JPY`, `1.2345 KWD`) | 400 `AMOUNT_SCALE_EXCEEDED` |
| not an ISO 4217 code with a minor unit (`XYZ`, `EURO`, `XAU`, blank) | 400 `CURRENCY_UNSUPPORTED` |
| absent amount/currency, more than 19 integer digits | 400 `VALIDATION_ERROR` |
| hold/credit/debit `amount` of zero or less; negative overdraft limit | 400 `VALIDATION_ERROR` |

The body is the platform RFC 9457 `ProblemDetail`; `violations[].field` names the field and the
rejected value is never echoed. Nothing is reserved, booked or announced and the `referenceId`
stays free, so a corrected retry applies. Never rounded: trailing zeros are fine (`10.5000 EUR` is
`10.50`), case and blanks in the currency are ignored (`eur` is `EUR`).

Valid input stores the same `NUMERIC(19,4)` value as before. The hold response and the
`HOLD_PLACED` / `BALANCE_UPDATED` event `amount` now carry the currency scale (`10.50` for a
request of `10.5`, `250.00` for `250`); balance reads still come from the `NUMERIC(19,4)` columns
unchanged, and no read path builds `Money`, so stored rows cannot fail to load.

**Kafka consumers.** `ledger-events-in` builds the `AccountBookedChanged` `delta` as `Money`;
`balance-init-in` builds the `AccountCreated` currency as a kernel `CurrencyCode`. A refusal is
rethrown, so the channel's `failure-strategy: dead-letter-queue` parks the record on
`openbank.dlq.balance.ledger-events-in` / `openbank.dlq.balance.balance-init-in` (with the original
String payload) and the channel continues — no dedup marker, pocket, balance change or event is
written. Zero and negative deltas remain valid (they are posted ledger facts).

## Error model (unified `openbank-libs.api.ApiError`)

| HTTP | code | When |
|---|---|---|
| 400 | `AMOUNT_SCALE_EXCEEDED` / `CURRENCY_UNSUPPORTED` / `VALIDATION_ERROR` | amount + currency are not a kernel `Money`, or a non-positive movement (see above) |
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
| `BALANCE_UPDATED` | credit/debit, ledger projection or value-date roll | eventId, accountId, currency, bookedAmount, availableAmount, reservedAmount, occurredAt |
| `HOLD_PLACED` | hold creation | eventId, accountId, currency, amount, availableAmount, occurredAt |
| `HOLD_RELEASED` | hold release or ledger cover consumption | eventId, accountId, currency, amount, availableAmount, occurredAt |

`balance.low.v1` is planned in ADR-0333 and is not emitted. Account-level opt-in, a downward
threshold crossing, cooldown and ownership checks must exist before it becomes a customer intent.

## Backward compatibility

- API `/api/v1/...`. v2 runs in parallel for 6 months.
- Event version in topic name + per-event `v1` suffix in the type.
- OpenAPI diff in CI prevents breaking changes without a bump.
