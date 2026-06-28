# ADR-0110 — Same-account FX pocket exchange

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Delivery-Status** | Shipped |
| **Date** | 2026-06-28 |
| **Deciders** | Platform Team |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

Customers who hold multiple-currency pockets on a single account need a first-class exchange path that
converts funds from one pocket currency to another without routing through a payment instruction.
Prior to this ADR such a conversion required two separate payment flows with no atomic guarantees and
no FX rate record.

## Decision

`account-service` exposes a new endpoint:

```
POST /api/v1/accounts/{accountId}/pockets/{fromCurrency}/exchange
```

The call is idempotent (requires `Idempotency-Key` header). The service:

1. Validates account ownership and `ACTIVE` status.
2. Calls `fx-service POST /api/v1/fx/convert` to obtain the applied rate and converted amount;
   the FX service records the conversion for audit and computes the bank spread.
3. Calls `transaction-service` twice — a DEBIT on the source pocket and a CREDIT on the target pocket —
   each with a deterministic idempotency key (`{key}-debit` / `{key}-credit`). If step 3 fails after
   step 2 succeeded, replaying the original request retries both transaction calls idempotently.
4. Returns `ExchangeResult` with `conversionId`, both amounts, and `appliedRate`.

The `FxConversionPort` (outbound) isolates the FX rate concern; `FxSettlementPort` (outbound) isolates
the transaction-service booking. Both are injected into `AccountService` following the hexagonal
architecture mandate (ADR-0002).

## Consequences

- **Positive:** Customers can exchange between pockets atomically from the mobile or admin UI without
  a payment instruction. The FX service records every conversion for regulatory reporting.
- **Positive:** Idempotency at both the FX and transaction layers means safe client retries.
- **Negative:** Two sequential REST calls (FX + transaction debit + transaction credit) increase
  end-to-end latency. A failure between the FX record and the transaction calls leaves a dangling
  FX record — acceptable given the idempotent retry path recovers it.
- **Negative:** `account-service` now depends on `fx-service` in addition to `transaction-service`.
  This adds a new failure mode: FX service unavailability blocks pocket exchanges. Circuit-breaker
  configuration is inherited from the Quarkus REST client defaults.

## Alternatives considered

- **Single FX-service endpoint that calls transaction-service internally:** would couple fx-service to
  the transaction book — rejected to keep fx-service pure (rate data, not settlement).
- **Outbox event from account-service:** eventual consistency complicates the client's "exchange now"
  UX and the FX rate may change between publish and consume — rejected.
