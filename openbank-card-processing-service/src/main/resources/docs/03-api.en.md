# API

The contract is `src/main/resources/openapi.yaml` (`info.version` 1.0.0, URL major `/api/v1`). Swagger UI: `/api/docs` on port 8157.

## Endpoints

| Method | Path | Roles | OPA action | Notes |
|---|---|---|---|---|
| POST | `/api/v1/card-authorizations` | `ROLE_API`, `ROLE_OPERATOR`, `ROLE_ADMIN` | `cardprocessing.authorize` | `Idempotency-Key` required. **201** for both approval and decline |
| GET | `/api/v1/card-authorizations/{id}` | same | `cardprocessing.read` | 404 if unknown |
| POST | `/api/v1/card-authorizations/{id}/clearing` | same | `cardprocessing.clear` | `Idempotency-Key` required. 200 or 409 |
| POST | `/api/v1/card-authorizations/{id}/reversal` | same | `cardprocessing.reverse` | 200 or 409 |
| GET | `/api/v1/card-authorizations/card/{cardId}` | same | `cardprocessing.read` | newest first; `limit` default 50, clamped to 1..200 |
| POST | `/api/v1/sandbox/acquirer/purchase` | `ROLE_ADMIN` | `cardprocessing.simulate` | **404 unless** `openbank.card-processing.sandbox-acquirer-enabled=true` |

## Authorisation request

Fields: `cardId`, `amountMinorUnits`, `currencyCode`, `channel` (`CONTACTLESS`, `ONLINE`, `ATM`, `CHIP_AND_PIN`), optional `mcc`, `merchantName`, `merchantCountry`, `networkReference`. No PAN or card credential is accepted.

A decline is a created record of a decision, so it is **201**, not 4xx; the response carries `status: DECLINED` and the `declineReason` from card-issuance.

## Idempotency

- **Authorisation:** the `Idempotency-Key` is stored on the row under a UNIQUE index; a repeated key returns the first authorisation unchanged and takes no second hold.
- **Clearing:** the key is required and is forwarded to transaction-service as `card-clearing:<key>` for the ledger posting. The service does not look the clearing key up before applying the presentment, so a repeated presentment is applied again if it still fits within the remaining hold.
- **Sandbox purchase:** `idempotencyKey` in the body is used as both the authorisation key and the network reference; the clearing key is `<key>:clearing`.

## Error and refusal model

| Situation | Status |
|---|---|
| Missing or blank `Idempotency-Key`, non-positive amount, currency ≠ card currency | 400 (`IllegalArgumentException` mapped by libs-runtime) |
| Card unknown to card-issuance | 404 |
| Clearing/reversal refused by the lifecycle | **409** with `{ reason, message }` |

`reason` is one of `NOT_HOLDING_FUNDS` (terminal state, or unknown authorisation id), `AMOUNT_NOT_POSITIVE`, `EXCEEDS_AUTHORIZED_AMOUNT`, `CURRENCY_MISMATCH`, `NOT_YET_EXPIRED` (expiry only, internal).

## Events

Published on `openbank.card.processing.events`, documented in `openbank-contracts/openbank-card-processing-service/asyncapi.yaml`:

| Event type | When | Notable fields |
|---|---|---|
| `card.authorised.v1` | approval | amount, currency, channel, mcc, category, merchant, `expiresAt` |
| `card.declined.v1` | decline | `reason` (card-issuance's decline reason, verbatim) |
| `card.cleared.v1` | accepted clearing | `clearedAmountMinorUnits`, `cumulativeClearedMinorUnits`, `fullyCleared` |
| `card.hold_released.v1` | reversal or expiry | `releasedAmountMinorUnits`, `releaseKind` (`REVERSAL` / `EXPIRY`) |

All carry `authorizationId`, `cardId`, `occurredAt` and `sourceService = card-processing-service`.
