<!-- SPDX-License-Identifier: Apache-2.0 -->
# Settlement through the ledger projection

This rollout changes who writes booked customer money. The new workflow reserves payer cover,
posts one balanced ledger journal, and lets the ledger event project both account legs. It never
calls the legacy direct debit, credit or reversal operations.

## Activation prerequisites

1. Deploy the balance implementation that atomically applies the ledger delta and consumes only
   the matching account/currency/transaction cover. Verify event delivery and DLQ replay in the
   target environment, including payee-first delivery and a lost acknowledgement.
2. Apply settlement V3 and deploy workers registering both workflow types. Keep
   `SETTLEMENT_LEDGER_PROJECTION_ENABLED=false` until every worker on the queue supports the new type.
3. Demonstrate payer reservation and journal posting with enforced OIDC/OPA and required four-eyes
   approval. Advisory-mode tests do not satisfy this prerequisite. Verify that the configured GL
   accounts are the intended customer deposit-control accounts.
4. Reconcile legacy in-flight settlements and any historic double application. Do not change their
   stored protocol or delete Temporal history to force them through the new workflow.
5. After review, enable the flag for new originations. Replaying the same idempotency key keeps the
   original stored protocol. Verify exactly one journal and one projected movement per account leg,
   released cover, balanced control accounts and no stranded/unknown outcomes.

## Uncertain outcomes

An exhausted activity retry does not establish whether its remote write committed. Cover remains
reserved when the outcome is unknown. Reconcile by settlement ID against the hold reference,
ledger transaction ID, projection markers and outbox delivery before any approved correction.
Never release a hold merely because the caller timed out. Never reissue the transfer under a new
idempotency key to clear a stalled row. `BOOKED` confirms the journal, not completion of asynchronous
projection on both balance pockets.

## Rollback

Disable the origination flag first. Keep the upgraded workers and balance projector running until
all `LEDGER_PROJECTION` histories have completed or been reconciled. Retain the additive protocol
column and its stored values. An older worker cannot execute the new workflow type; an older balance
projector cannot provide the atomic cover guarantee. Binary rollback before draining these histories
is not safe. Rollback does not undo already posted journals or repair historic balance drift.

## Legacy direct movement uncertainty

A legacy workflow's `BALANCE_STATE_UNKNOWN` means a debit or credit exhausted its attempts
without a reliable completion result. The movement may already have committed. The REST response
uses `PENDING`, `recoveryRequired=true` and `recoveryReason=BALANCE_STATE_UNKNOWN`.
The workflow stops further money movements, journal booking and rejection; the
`SettlementBalanceStateUnknown` alert identifies outstanding cases.

Reconcile the original movement references and establish that no original activity or request can
still commit before applying an approved correction. An absent row during an in-flight request
is not proof of non-execution. Retain the evidence and reconcile customer balances with the ledger.
Do not apply direct balance corrections to a ledger-projection settlement: its journal events
already own both balance movements.

The legacy uncertainty guard uses a Temporal version marker. Existing histories replay their
previous command sequence; the guard cannot repair already completed settlements. Keep compatible
workers while old executions remain and reconcile pre-existing ambiguous cases separately.
