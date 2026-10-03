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

An exhausted activity retry does not establish whether its remote write committed. The settlement
workflow never releases cover on that uncertainty alone. If the ledger did commit, its confirmed
journal event can still project the movement and atomically consume the matching cover while the
settlement remains `LEDGER_STATE_UNKNOWN`. Otherwise the cover remains reserved. Reconcile by
settlement ID against the hold reference,
ledger transaction ID, projection markers and outbox delivery before any approved correction.
The local proof `--drop-cover-responses` exercises five lost replies after a real hold commits: one active hold remains, no journal activity starts, and the row is `BALANCE_STATE_UNKNOWN`. By contrast, `--reject-cover` exercises insufficient funds with no hold created. Both currently produce the same uncertainty state; operators must inspect the actual hold and workflow history rather than infer reservation existence from that status.
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

## Local response-loss proof

The isolated three-service harness at `openbank-infra/scripts/settlement-real-services-e2e.py`
exercises real OIDC/OPA, Temporal, journal posting and Kafka balance projection. Its
`--drop-ledger-response` mode loses one reply after the ledger returns `POSTED` and requires the
retry to resolve to the same journal. `--drop-ledger-response 5` loses all five activity replies and
requires `LEDGER_STATE_UNKNOWN` with exactly one journal, correctly projected balances and consumed
cover. These modes verify safe uncertainty handling, not completed operator reconciliation or
sandbox rollout acceptance. See the adjacent harness README for prerequisites and evidence.

## Recover a confirmed posting after exhausted replies

This procedure is limited to `LEDGER_PROJECTION` settlements in `LEDGER_STATE_UNKNOWN` where the
original journal is positively established as `POSTED`. It does not authorize financial corrections,
new transfers, legacy saga recovery, or reset of an in-flight execution.

1. Use the approved operator access to inspect the exact settlement and its Temporal run. Retain
   the completed original history. Confirm the execution is closed and no original activity is
   still outstanding.
2. Read the ledger by the original settlement transaction ID. Require exactly one `POSTED` journal
   with the intended payer/payee subaccounts, currency, amount and balanced legs. Reconcile both
   projected balances, the matching hold and projection/outbox delivery. Missing or contradictory
   evidence requires investigation; a timeout or an empty lookup does not authorize a reset.
3. In the original history, locate the `ActivityTaskScheduled` event whose activity type is
   `BookToLedger`. Use its `workflowTaskCompletedEventId` as the reset point. The successful cover
   reservation must precede this point. Do not select the first workflow task or reset a different
   workflow type merely because its name looks similar.
4. Through the existing authorized Temporal operator interface, reset that exact workflow ID and
   original run ID at the selected event, recording the recovery reason. Preserve history and use
   the compatible worker binary. Do not change the settlement, idempotency key, or stored protocol.
5. Verify the new run completes, the durable settlement becomes `BOOKED`, and its transition audit
   is present. Verify the same single journal ID and unchanged customer balances; the recovery
   must not create another journal or release any unrelated reservation. If it fails, retain the
   new evidence and investigate rather than repeatedly resetting.

The harness option `--drop-ledger-response 5 --recover-after-loss` exercises the same history-based
reset with isolated infrastructure. The runtime has no automatic reset loop. Access control for the
operator interface and approval evidence must be verified separately in the deployment environment.

## Worker process loss

New ledger-projection activity schedules use a one-minute Start-To-Close timeout within the
existing two-hour Schedule-To-Close budget and five-attempt limit. This permits retry when a
worker dies without reporting completion; the total deadline alone previously gave an individual
attempt the full two hours, leaving no budget for recovery after it timed out. Retry retains the
same settlement identity and relies on the original hold/journal idempotency keys. A timeout
never establishes whether a remote write committed.

`--crash-worker-after-ledger-commit` verifies same-run recovery after killing the local worker
once the ledger confirms POSTED but before its reply reaches settlement. This exercises the
new workflow only. Already scheduled activities retain the timeouts in their Temporal history;
upgrading a worker does not shorten those timers or repair a legacy saga. Inspect the recorded
activity deadlines and preserve the existing reconciliation procedure for those histories.
