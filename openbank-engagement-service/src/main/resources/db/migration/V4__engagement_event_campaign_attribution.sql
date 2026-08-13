-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Server-resolved campaign context for privacy-safe aggregate analytics (issue #4535). Every
-- column is nullable so organic and historic events remain honestly unattributed. The check keeps
-- partial or non-PUSH attribution out of the append-only history even if a future caller bypasses
-- the REST invariant. Rollback: DROP CONSTRAINT chk_engagement_campaign_attribution, then DROP
-- campaign_channel, campaign_step_order and campaign_id from engagement_event.

ALTER TABLE engagement_event
    ADD COLUMN campaign_id UUID,
    ADD COLUMN campaign_step_order INTEGER,
    ADD COLUMN campaign_channel VARCHAR(16),
    ADD CONSTRAINT chk_engagement_campaign_attribution CHECK (
        (campaign_id IS NULL AND campaign_step_order IS NULL AND campaign_channel IS NULL)
        OR
        (campaign_id IS NOT NULL AND campaign_step_order >= 0 AND campaign_channel = 'PUSH')
    );

CREATE INDEX idx_engagement_event_campaign
    ON engagement_event (campaign_id, campaign_step_order, campaign_channel)
    WHERE campaign_id IS NOT NULL;
