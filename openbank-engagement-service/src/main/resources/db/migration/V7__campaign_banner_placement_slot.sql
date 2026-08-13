-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- Existing banner placements retain their original home surface through the explicit default.
ALTER TABLE campaign_banner_placement
    ADD COLUMN slot VARCHAR(32) NOT NULL DEFAULT 'HOME_BANNER';

ALTER TABLE campaign_banner_placement
    ADD CONSTRAINT chk_campaign_banner_placement_slot CHECK (
        slot IN ('HOME_BANNER', 'HOME_CAROUSEL', 'PRODUCT_FEED', 'REWARDS_HUB')
    );

CREATE INDEX idx_campaign_banner_placement_party_slot_placed
    ON campaign_banner_placement (party_id, slot, placed_at DESC);

-- Rollback: drop the index and constraint, then the slot column after traffic returns to HOME_BANNER only.
