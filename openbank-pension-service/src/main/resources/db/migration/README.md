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
