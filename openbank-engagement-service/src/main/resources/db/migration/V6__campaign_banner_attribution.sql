-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

ALTER TABLE engagement_event DROP CONSTRAINT chk_engagement_campaign_attribution;
ALTER TABLE engagement_event
    ADD CONSTRAINT chk_engagement_campaign_attribution CHECK (
        (campaign_id IS NULL AND campaign_step_order IS NULL AND campaign_channel IS NULL)
        OR
        (campaign_id IS NOT NULL AND campaign_step_order >= 0 AND campaign_channel IN ('PUSH', 'BANNER'))
    );

-- Rollback: restore V4's PUSH-only constraint after removing BANNER-attributed event rows.
