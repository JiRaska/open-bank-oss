# pension-service Flyway migrations

There is **no `V8`**, and that is intended. No `V8` was ever committed to this module (the history
holds none); `V9` was numbered while parallel ADR-0334 slices were still allocating versions, and
the number was never used. Flyway orders by version and does not require contiguous numbers, so
the gap is harmless.

Do **not** fill it and do not renumber `V9+`: Flyway checksums every applied file, and an
out-of-order `V8` added later is refused by default (`outOfOrder=false`). The next migration takes
the next free number after the highest one present.


### V14 — immutable fund order plans

The production fund adapter saves every complete plan before the first register call. A retry loads the same plan, even when NAV, holdings or strategy allocations have changed. Keep these rows for as long as the associated operations can be retried; deleting a row would allow a different instruction to be calculated under the same key. The migration is additive and does not rewrite existing data.

The production adapter is introduced with this integration, so the initial rollout starts with journaled operations. Later downgrades require stopping fund writes and retries before switching to an adapter without this journal. Retain the table and reconcile outstanding saved plans before considering removal; no automated downgrade drops it.

### V15 — source settlement time for reporting

Adds nullable `settled_at` and `settlement_event_revision` to payout instructions. Deploy the additive domestic-payment event field before upgrading the pension consumer. Historical rows are not backfilled from processing or order time; reporting refuses an unknown settlement time. The SQL file documents reconciliation and rollback, including taking a backup before removing the recorded timestamps.

This draft migration follows the integration branch's V14 fund-order journal. Its number is reserved as V15 before deployment; no applied migration is renumbered.

### V16 — immutable participant aggregate source snapshots

Adds aggregate-only technical source snapshots and successful capture command receipts. The
captured JSON bytes and SHA-256 are immutable; correction is a new revision, while a retry with
the same actor-scoped key returns the original receipt. This does not establish a legal or
business close or a source-specific retention period. Deploy schema before the capture API.
Before any write, the objects can be dropped for rollback. After capture, keep the append-only
evidence and roll back application readers/writers only; deletion needs a separately reviewed
retention and reconciliation procedure. No historical source revision is fabricated.
