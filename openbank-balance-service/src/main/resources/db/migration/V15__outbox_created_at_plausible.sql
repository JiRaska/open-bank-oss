-- #9081: fleet sweep of the guard proven on lending_outbox (#9003) and called for by #3272 —
-- an outbox row stamped 1970-01-01 (toEntity() assigning over the column DEFAULT) sorts ahead of
-- all real work in the dispatcher's ORDER BY created_at ASC and lands in the 1970-01 warehouse
-- partition. This service has no reported epoch-stamped rows (#3272 hit ledger, #9003 lending),
-- so this migration is the guard only; if the SELECT below ever answers non-zero, correct those
-- rows to NOW() before adding the constraint:
--   SELECT count(*) FROM balance_outbox WHERE created_at < TIMESTAMPTZ '2020-01-01';
--
-- Rollback: ALTER TABLE balance_outbox DROP CONSTRAINT IF EXISTS balance_outbox_created_at_plausible;
ALTER TABLE balance_outbox
    ADD CONSTRAINT balance_outbox_created_at_plausible
    CHECK (created_at >= TIMESTAMPTZ '2020-01-01');
