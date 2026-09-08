-- #9003: 44 of 45 lending_outbox rows on the sandbox carry created_at/updated_at/sent_at of
-- 1970-01-01 — the #3272 defect class (toEntity() assigning over the column DEFAULT) from before
-- OutboxMessage.createdAt gained its Instant.now() default. No src/main construction site passes
-- an explicit createdAt today (grep-verified), so this migration is the data half plus the guard.
--
-- Data: the rows are all SENT, so correcting them changes no pending dispatch order; leaving them
-- would keep 44 epoch rows sorting ahead of real work forever (dispatcher claims ORDER BY
-- created_at ASC) and landing in the 1970-01 bronze_events partition if ever replayed into the
-- warehouse. Their true business time is unrecoverable — the honest value is the correction
-- instant, and this UPDATE is itself the audit trail of that.
UPDATE lending_outbox
SET created_at = NOW(),
    updated_at = NOW(),
    sent_at = NOW()
WHERE created_at < TIMESTAMPTZ '2020-01-01';

-- Guard (the issue's suggested work item 3): no outbox row may be inserted with a created_at
-- older than any plausible service lifetime. Decidable, cheap, and would have caught both this
-- and #3272 at INSERT time instead of in a warehouse partition.
--
-- Rollback: ALTER TABLE lending_outbox DROP CONSTRAINT IF EXISTS lending_outbox_created_at_plausible;
ALTER TABLE lending_outbox
    ADD CONSTRAINT lending_outbox_created_at_plausible
    CHECK (created_at >= TIMESTAMPTZ '2020-01-01');
