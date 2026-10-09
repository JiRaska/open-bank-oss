-- Expand-only: a company receipt is bound to the representation mandate verified at creation.
-- Older rows remain NULL and cannot be disclosed to a newly granted representative.
-- Rollback: previous binaries ignore this nullable column; retain it for forward recovery.
ALTER TABLE standing_orders ADD COLUMN initiating_mandate_id UUID;
