# Ledger outbox V2 cutover

See the [service runbook](svc-ledger.md), ADR-0327 and [issue #11652](https://github.com/JiRaska/open-bank-oss/issues/11652).

The ledger deployment hold applies to this change. Schedule the cutover through
the normal GitOps review after the hold conditions are satisfied.

1. Roll out the compatibility release that explicitly projects the V1 claim's
   mapped columns. Confirm every ledger pod runs that release before applying V45.
   A binary that still uses `RETURNING *` can fail when V45 adds unmapped columns.
2. Quiesce dispatch through GitOps with `OPENBANK_OUTBOX_DISPATCH_ENABLED=false`.
   Confirm every pod has the flag and no earlier pod can still dispatch. Allow the
   two-minute claim lease to expire after the last active tick. Ledger writes
   still commit their outbox rows and backlog can grow while the relay is paused.
3. Roll out the V2 binary with dispatch still disabled. Verify Flyway V45, both
   valid partial indexes, pod readiness and the absence of V1 pods before
   enabling dispatch. For a large table, follow the concurrent index preparation
   in ADR-0327 D2 before the Flyway rollout.
4. Enable dispatch through the reviewed GitOps change. Verify committed sends,
   falling backlog and normal retry and dead-letter metrics before closing the cutover.

To roll back, quiesce the V2 dispatcher and wait for active leases to expire.
Return to the compatibility release with dispatch disabled, verify the V2 pods
have stopped, then enable dispatch. Retain V45's nullable columns and indexes.
A paused process can send a duplicate before it discovers its lease was
replaced. Consumers still deduplicate by event ID.
