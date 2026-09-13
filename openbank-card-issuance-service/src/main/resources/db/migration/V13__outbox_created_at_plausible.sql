-- #9081: fleet sweep of the guard proven on lending_outbox (#9003). Unlike the other services in
-- the sweep, card-issuance DOES carry the defect live: CardOutboxRequeueIT persists DEAD rows
-- stamped 1970-01-01 "so the test data is the shape actually stranded in the cluster" (#3272).
-- So this migration is BOTH halves, in the lending V16 shape.
--
-- Data: the stranded rows are DEAD or requeued-SENT history, so correcting them changes no
-- pending dispatch order; leaving them would keep epoch rows sorting ahead of real work forever
-- (dispatcher claims ORDER BY created_at ASC). Their true business time is unrecoverable — the
-- honest value is the correction instant, and this UPDATE is itself the audit trail of that.
UPDATE card_outbox
SET created_at = NOW(),
    updated_at = NOW(),
    sent_at = NOW()
WHERE created_at < TIMESTAMPTZ '2020-01-01';

-- Guard: no outbox row may be inserted with a created_at older than any plausible service
-- lifetime. Would have caught #3272 at INSERT time.
--
-- Rollback: ALTER TABLE card_outbox DROP CONSTRAINT IF EXISTS card_outbox_created_at_plausible;
ALTER TABLE card_outbox
    ADD CONSTRAINT card_outbox_created_at_plausible
    CHECK (created_at >= TIMESTAMPTZ '2020-01-01');
