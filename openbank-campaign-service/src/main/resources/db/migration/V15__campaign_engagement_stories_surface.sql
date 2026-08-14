-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Stories are already a closed app surface in the campaign API and message contracts.  This is an
-- expand-only correction to the aggregate projection's older CHECK constraint: without it, a
-- legitimate attributable STORY event reaches the campaign consumer but PostgreSQL rejects its
-- projection row and the Kafka record retries forever.
--
-- Mixed-version safety: existing readers and writers remain valid; this only admits the new
-- closed value.  Rollback of application code is safe with this widened constraint left in place.
-- Do NOT restore the former constraint while STORY rows exist: that would require deleting derived
-- history, which is a separate, explicitly authorised retention operation rather than rollback.

ALTER TABLE campaign_engagement_event
    DROP CONSTRAINT campaign_engagement_event_surface_check;

ALTER TABLE campaign_engagement_event
    ADD CONSTRAINT campaign_engagement_event_surface_check CHECK (
        surface IN ('HOME_BANNER', 'HOME_CAROUSEL', 'STORIES', 'PRODUCT_FEED', 'REWARDS_HUB')
    );
