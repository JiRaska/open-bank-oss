-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Opaque, validated campaign PUSH handoff reference (issue #4480). Nullable keeps historic and
-- non-campaign surface events explicitly unattributed. Rollback: DROP INDEX
-- idx_engagement_event_interaction_ref; ALTER TABLE engagement_event DROP COLUMN interaction_ref;

ALTER TABLE engagement_event ADD COLUMN interaction_ref UUID;

-- Supports a later bounded attribution aggregate without scanning the append-only event history.
CREATE INDEX idx_engagement_event_interaction_ref
    ON engagement_event (interaction_ref)
    WHERE interaction_ref IS NOT NULL;
