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

## Částka a měna na vstupu (#11604)

Každý vstup nesoucí peníze se nejdřív postaví jako kernel `Money` (`Money.parseInbound`) — dřív než
replay lookup holdu `(accountId, currency, referenceId)` (ADR-0287), dřív než `referenceId` marker
pohybu pro credit/debit (V8), dřív než jakýkoli zápis a outbox event. Platí pro `POST /holds`,
`/credit`, `/debit` (`amount` + `currency`), `/initialize` (`initialAmount`,
`arrangedOverdraftLimit` + `currency`) a `PUT /{currency}/overdraft-limit` (`arrangedOverdraftLimit`).

| Vstup | Odpověď |
|---|---|
| víc desetinných míst, než měna dovoluje (`10.005 EUR`, `1000.5 JPY`, `1.2345 KWD`) | 400 `AMOUNT_SCALE_EXCEEDED` |
| není ISO 4217 kód s minor unit (`XYZ`, `EURO`, `XAU`, prázdný) | 400 `CURRENCY_UNSUPPORTED` |
| chybějící částka/měna, víc než 19 celých číslic | 400 `VALIDATION_ERROR` |
| `amount` holdu/creditu/debitu nula nebo méně; záporný overdraft limit | 400 `VALIDATION_ERROR` |

Tělo je platformní RFC 9457 `ProblemDetail`; `violations[].field` jmenuje pole a odmítnutá hodnota se
nikdy nevrací. Nic se nerezervuje, nezaúčtuje ani neohlásí a `referenceId` zůstává volné, takže
opravený retry projde. Nikdy se nezaokrouhluje: koncové nuly nevadí (`10.5000 EUR` je `10.50`),
velikost písmen a mezery v měně se ignorují (`eur` je `EUR`).

Validní vstup uloží stejnou hodnotu `NUMERIC(19,4)` jako dřív. Odpověď holdu a `amount` v eventech
`HOLD_PLACED` / `BALANCE_UPDATED` nově nesou scale měny (`10.50` pro požadavek `10.5`, `250.00` pro
`250`); čtení zůstatků jde dál beze změny ze sloupců `NUMERIC(19,4)` a žádná čtecí cesta `Money`
nestaví, takže uložené řádky nemůžou selhat při načtení.

**Kafka konzumenti.** `ledger-events-in` staví `delta` z `AccountBookedChanged` jako `Money`;
`balance-init-in` staví měnu z `AccountCreated` jako kernel `CurrencyCode`. Odmítnutí se znovu
vyhodí, takže `failure-strategy: dead-letter-queue` kanálu odloží záznam do
`openbank.dlq.balance.ledger-events-in` / `openbank.dlq.balance.balance-init-in` (s původním String
payloadem) a kanál pokračuje — nezapíše se dedup marker, pocket, změna zůstatku ani event. Nulová a
záporná delta zůstává validní (jde o zaúčtovaná fakta z ledgeru).

## Error model (jednotný `openbank-libs.api.ApiError`)

| HTTP | code | Kdy |
|---|---|---|
| 400 | `AMOUNT_SCALE_EXCEEDED` / `CURRENCY_UNSUPPORTED` / `VALIDATION_ERROR` | částka + měna nejsou kernel `Money`, nebo nekladný pohyb (viz výše) |
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
| `BALANCE_UPDATED` | kredit/debet, projekce ledgeru nebo value-date roll | eventId, accountId, currency, bookedAmount, availableAmount, reservedAmount, occurredAt |
| `HOLD_PLACED` | vytvoření blokace | eventId, accountId, currency, amount, availableAmount, occurredAt |
| `HOLD_RELEASED` | uvolnění blokace nebo spotřeba krytí v ledgeru | eventId, accountId, currency, amount, availableAmount, occurredAt |

`balance.low.v1` je plánované v ADR-0333 a dnes se neemituje. Před vznikem zákaznického záměru
musí být nastavení pro účet, sestup přes práh, cooldown a ověření vlastnictví.

## Backward compatibility

- API `/api/v1/...`. v2 = paralelní 6 měsíců.
- Event verze v topic name + per-event `v1` suffix v type.
- OpenAPI diff v CI bránící breaking změnám bez bumpu.
