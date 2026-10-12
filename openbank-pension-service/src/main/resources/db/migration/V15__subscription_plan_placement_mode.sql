-- Plans created before the provider batch API used individual leg calls. Preserve their replay
-- mode, including partially accepted plans; only newly created plans opt into atomic placement.
-- Rollback: keep this column and batch replay behavior while any BATCH plan can be redelivered.
ALTER TABLE pension_subscription_plans
    ADD COLUMN placement_mode VARCHAR(8) NOT NULL DEFAULT 'LEGACY'
        CHECK (placement_mode IN ('LEGACY', 'BATCH'));
