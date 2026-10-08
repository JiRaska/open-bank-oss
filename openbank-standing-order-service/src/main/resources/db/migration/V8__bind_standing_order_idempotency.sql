-- Historical rows have no provable original payload or customer actor. Keep these nullable;
-- a receipt lookup must report UNKNOWN for them rather than inventing a provenance match.
ALTER TABLE standing_orders
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD COLUMN customer_actor_id UUID,
    ADD COLUMN replaces_standing_order_id UUID;

-- Rollback: drop these three columns only after all new rows are retired. Dropping them
-- sooner destroys the retry binding and must never be done on a live payment path.
