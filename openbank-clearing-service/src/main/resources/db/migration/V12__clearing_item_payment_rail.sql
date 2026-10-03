-- A pending legacy item has no trustworthy rail: its sentinel batch has a default SEPA_SCT
-- rail unrelated to the submitted request. Leave it NULL and block clearing until an operator
-- can reconcile the source payment. Existing assigned items also remain NULL: the old
-- selector ignored rail, so even their batch rail cannot prove the submitted rail.
-- Rollback: after reverting code that reads the item rail, DROP INDEX IF EXISTS
-- idx_clearing_items_pending_rail_currency; ALTER TABLE clearing_items DROP COLUMN rail;
ALTER TABLE clearing_items ADD COLUMN rail payment_rail;

CREATE INDEX idx_clearing_items_pending_rail_currency
    ON clearing_items (rail, currency, created_at, id)
    WHERE status = 'PENDING';
