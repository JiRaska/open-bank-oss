-- #12004: a clearing item records the rail it was submitted for, so a cycle for one rail selects
-- only that rail's PENDING items. Nullable with NO default on purpose: a default would assign a
-- rail nobody chose, and a cycle for that rail would then net and settle the item on it. A row
-- with rail IS NULL (only rows written before this migration; the sandbox held 0 items when it
-- was written) is selected by NO cycle -- it stays PENDING and unbatched, and every cycle logs a
-- WARN with the count until an operator sets the rail by hand. The application always writes it.
--
-- #12005: cycle_id was VARCHAR(32), and "CYCLE-SEPA_SCT_INST-YYYYMMDD-NNNN" is 33 characters, so
-- the first such cycle failed on insert. Widened to 64 in both tables that store it; the
-- application bounds the id to that width by construction (ClearingService.CYCLE_ID_MAX_LENGTH).
-- Widening a VARCHAR is metadata-only in Postgres (no rewrite) and no data changes.
--
-- Rollback (after rolling back the code that writes/reads clearing_items.rail):
--   ALTER TABLE clearing_items DROP COLUMN rail;
--   DROP INDEX IF EXISTS idx_clearing_items_pending_rail;
--   ALTER TABLE clearing_batches ALTER COLUMN cycle_id TYPE VARCHAR(32);       -- fails if a longer id exists
--   ALTER TABLE settlement_positions ALTER COLUMN cycle_id TYPE VARCHAR(32);   -- idem
ALTER TABLE clearing_items ADD COLUMN rail payment_rail;

CREATE INDEX idx_clearing_items_pending_rail
    ON clearing_items (rail, currency, created_at)
    WHERE status = 'PENDING';

ALTER TABLE clearing_batches ALTER COLUMN cycle_id TYPE VARCHAR(64);
ALTER TABLE settlement_positions ALTER COLUMN cycle_id TYPE VARCHAR(64);
