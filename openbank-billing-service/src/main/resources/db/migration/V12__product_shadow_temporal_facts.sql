-- Existing queued rows retain NULL: their assessment facts cannot be reconstructed safely.
ALTER TABLE billing_product_shadow_input
    ADD COLUMN evaluated_at TIMESTAMPTZ,
    ADD COLUMN evaluation_zone TEXT,
    ADD COLUMN opened_at TIMESTAMPTZ,
    ADD COLUMN scheduled_cadence TEXT,
    ADD COLUMN scheduled_trigger TEXT;
-- Rollback: stop the shadow worker, deploy the previous writer, then drop these five columns.
-- Never backfill them from queue processing time or assume a cycle-id string identifies a trigger.
