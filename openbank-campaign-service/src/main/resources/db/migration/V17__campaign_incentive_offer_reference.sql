-- Immutable published incentive revision selected by Campaign Studio. Redemption and value mutation
-- remain owned by incentive-service; these columns are intentionally nullable for legacy campaigns.
ALTER TABLE campaigns
    ADD COLUMN incentive_offer_id UUID,
    ADD COLUMN incentive_offer_name VARCHAR(160),
    ADD COLUMN incentive_offer_version INTEGER,
    ADD CONSTRAINT campaigns_incentive_offer_complete_ck CHECK (
        (incentive_offer_id IS NULL AND incentive_offer_name IS NULL AND incentive_offer_version IS NULL)
        OR
        (incentive_offer_id IS NOT NULL AND incentive_offer_name IS NOT NULL AND incentive_offer_version > 0)
    );

-- Rollback: remove campaigns_incentive_offer_complete_ck, then the three incentive_offer_* columns.
