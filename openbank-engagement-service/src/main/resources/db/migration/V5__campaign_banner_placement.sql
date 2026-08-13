-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

CREATE TABLE campaign_banner_placement (
    interaction_ref UUID PRIMARY KEY,
    party_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    step_order INTEGER NOT NULL,
    template VARCHAR(128) NOT NULL,
    values_json TEXT NOT NULL,
    deep_link VARCHAR(256) NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_campaign_banner_placement_party_placed
    ON campaign_banner_placement (party_id, placed_at DESC);
