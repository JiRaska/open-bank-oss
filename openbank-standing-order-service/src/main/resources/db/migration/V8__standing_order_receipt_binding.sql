-- Expand-only receipt binding (#12317). Pre-V8 rows stay NULL and resolve as UNKNOWN.
-- Rollback: deploy the previous binary; it ignores these nullable columns. Preserve them for
-- forward recovery; do not drop or backfill without a proven actor and request fingerprint.
ALTER TABLE standing_orders
    ADD COLUMN request_hash VARCHAR(64),
    ADD COLUMN initiating_principal TEXT,
    ADD COLUMN initiating_party_id UUID,
    ADD COLUMN initiating_actor_id UUID;
