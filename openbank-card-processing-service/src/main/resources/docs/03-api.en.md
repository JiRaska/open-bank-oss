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
| POST | `/api/v1/card-tokens` | `ROLE_API`, `ROLE_OPERATOR`, `ROLE_ADMIN` | `cardprocessing.token` | `Idempotency-Key` required. 201, 404, 409 or 503 |
| POST | `/api/v1/card-tokens/{tokenReference}/status` | same | `cardprocessing.token` | body `{ status }` — `ACTIVE`, `SUSPENDED`, `DELETED`; other values 400 |
| GET | `/api/v1/card-tokens/card/{cardId}` | same | `cardprocessing.read` | `{ tokens, source, degradedReason, count }` |
| POST | `/api/v1/card-disputes` | same | `cardprocessing.dispute` | `Idempotency-Key` required. 201, 400, 404 or 409 |
| POST | `/api/v1/card-disputes/{id}/evidence` | same | `cardprocessing.dispute` | `Idempotency-Key` required; body `{ documentReference, note }`; appended to the evidence history |
| GET | `/api/v1/card-disputes/{id}/evidence` | same | `cardprocessing.read` | the evidence history, oldest first |
| POST | `/api/v1/card-disputes/{id}/refresh` | same | `cardprocessing.dispute` | re-reads the network status; a closed case is returned unchanged |
| GET | `/api/v1/card-disputes/{id}` | same | `cardprocessing.read` | both `status` and `schemeStatus` |
| GET | `/api/v1/card-disputes/card/{cardId}` | same | `cardprocessing.read` | newest first; `limit` default 50, clamped to 1..200 |
| POST | `/api/v1/sandbox/acquirer/purchase` | `ROLE_ADMIN` | `cardprocessing.simulate` | **404 unless** `openbank.card-processing.sandbox-acquirer-enabled=true` |

## Authorisation request

Fields: `cardId`, `amountMinorUnits`, `currencyCode`, `channel` (`CONTACTLESS`, `ONLINE`, `ATM`, `CHIP_AND_PIN`), optional `mcc`, `merchantName`, `merchantCountry`, `networkReference`. No PAN or card credential is accepted.

A decline is a created record of a decision, so it is **201**, not 4xx; the response carries `status: DECLINED` and the `declineReason` from card-issuance.

## Idempotency

- **Authorisation:** the `Idempotency-Key` is stored on the row under a UNIQUE index; a repeated key returns the first authorisation unchanged and takes no second hold.
- **Clearing:** the key is required and is applied **at most once per authorisation**. Each applied clearing is recorded in `card_clearings` under a UNIQUE `(authorization_id, idempotency_key)` constraint, in the same transaction as the hold decrement and the `card.cleared.v1` event. A repeat with the same amount and currency (currency compared case-insensitively) replays the authorisation's current state with **200** — no second hold decrement, no second event, no second ledger posting. The same key with a different amount or currency is **409 `IDEMPOTENCY_KEY_REUSED`** (libs `ApiError` body). Two concurrent duplicates cannot both apply: the loser's insert fails on the constraint, its transaction rolls back whole, and it replays the winner. A refused presentment records nothing, so its retry is evaluated afresh. Concurrent clearings under **different** keys on one authorisation are serialised by an optimistic lock: the loser is re-evaluated once against the real remaining hold (applied, or 409 `EXCEEDS_AUTHORIZED_AMOUNT`); if it loses twice it answers **409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`** — retry later. The ledger posting is keyed `card-clearing:<authorizationId>:<key>` in transaction-service (scoped per authorisation; a key that would exceed transaction-service's 100-character column is replaced by `sha256-` + the first 32 hex characters of its SHA-256).
- **Sandbox purchase:** `idempotencyKey` in the body is used as both the authorisation key and the network reference; the clearing key is `<key>:clearing`.

## Error and refusal model

| Situation | Status |
|---|---|
| Missing or blank `Idempotency-Key`, non-positive amount, currency ≠ card currency | 400 (`IllegalArgumentException` mapped by libs-runtime) |
| Card unknown to card-issuance | 404 |
| Clearing/reversal refused by the lifecycle | **409** with `{ reason, message }` |
| Clearing key reused with a different amount or currency | **409** with libs `ApiError`, `code: IDEMPOTENCY_KEY_REUSED` |
| Clearing lost a concurrent-clearing race twice | **409** with libs `ApiError`, `code: IDEMPOTENCY_REQUEST_IN_PROGRESS` (retry later) |

`reason` is one of `NOT_HOLDING_FUNDS` (terminal state, or unknown authorisation id), `AMOUNT_NOT_POSITIVE`, `EXCEEDS_AUTHORIZED_AMOUNT`, `CURRENCY_MISMATCH`, `NOT_YET_EXPIRED` (expiry only, internal).

## Token and dispute refusals

Both resources answer a refusal as `{ reason, message }`. `CARD_NOT_FOUND`, `TOKEN_NOT_FOUND`, `AUTHORIZATION_NOT_FOUND` and `CASE_NOT_FOUND` are **404**; `ISSUER_UNAVAILABLE` (card-issuance could not be reached — fails closed, like authorisation) is **503**; every other reason is **409**.

- **Token:** `CARD_NOT_FOUND` (card-issuance does not know the card), `CARD_NOT_ACTIVE` (blocked, suspended, expired, cancelled or of unreported state — never tokenised), `ISSUER_UNAVAILABLE`, `TOKEN_NOT_FOUND`, `TOKEN_TERMINAL`, `SCHEME_UNAVAILABLE` (includes `NOT_BOUND`), `SCHEME_REFUSED`.
- **Dispute:** `AUTHORIZATION_NOT_FOUND`, `NO_NETWORK_REFERENCE`, `NOTHING_CLEARED`, `CURRENCY_MISMATCH` (the dispute currency is not the authorisation's; compared as Money, never converted — an unknown code is 400), `AMOUNT_EXCEEDS_CLEARED`, `ALREADY_DISPUTED`, `CASE_NOT_FOUND`, `CASE_TERMINAL`, `SCHEME_UNAVAILABLE`, `SCHEME_REFUSED`.

Idempotency: token provisioning, dispute opening and evidence filing **reserve** the `Idempotency-Key` in the database (`card_lifecycle_idempotency`) before the network is called. Of two concurrent requests with one key exactly one reaches the network; a retry after it finished replays the first result, a request racing it gets **409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`**, and the same key with a different body is **409 `IDEMPOTENCY_KEY_REUSED`** (fleet error shape). A refusal frees the key. Keys are 1–128 characters of `[A-Za-z0-9._:-]` (400 otherwise). Status changes and refresh take no key.

The OPA policy grants `cardprocessing.token` and `cardprocessing.dispute` to HUMAN principals with `ROLE_OPERATOR` or `ROLE_ADMIN` (reason `operator-card-lifecycle-write`); the role check on the resource also admits `ROLE_API`. While `AUTHZ_ENFORCE=false` the role check is the effective control.

## Events

Published on `openbank.card.processing.events`, documented in `openbank-contracts/openbank-card-processing-service/asyncapi.yaml`:

| Event type | When | Notable fields |
|---|---|---|
| `card.authorised.v1` | approval | amount, currency, channel, mcc, category, merchant, `expiresAt` |
| `card.declined.v1` | decline | `reason` (card-issuance's decline reason, verbatim) |
| `card.cleared.v1` | accepted clearing | `clearedAmountMinorUnits`, `cumulativeClearedMinorUnits`, `fullyCleared` |
| `card.hold_released.v1` | reversal or expiry | `releasedAmountMinorUnits`, `releaseKind` (`REVERSAL` / `EXPIRY`) |

| `card.token.provisioned.v1` | token provisioned | `registrationId`, `tokenReference`, `requestorId`, `requestorLabel`, `scheme`, `status`, `expiry` |
| `card.token.status_changed.v1` | token status changed | `previousStatus`, `status`, `scheme` |
| `card.dispute.opened.v1` | case opened | `disputeId`, `authorizationId`, `networkCaseId`, `reasonCode`, amount, `respondByDate`, `scheme` |
| `card.dispute.evidence_submitted.v1` | evidence filed | `disputeId`, `networkCaseId`, `documentReference` |
| `card.dispute.status_changed.v1` | refresh moved a status | `previousStatus`, `status`, `schemeStatus` |

All carry `cardId`, `occurredAt` and `sourceService = card-processing-service`; the authorisation and dispute events also carry `authorizationId`.
