-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- ADR-0220 D3 engagement profile store: opt-in flag, streak, points, badges.
-- party_id is the PK — one row per party; lazily created on first read.
-- Rollback: DROP TABLE engagement_profiles;
CREATE TABLE engagement_profiles (
    party_id         UUID                     NOT NULL PRIMARY KEY,
    enrolled         BOOLEAN                  NOT NULL DEFAULT FALSE,
    adverse_state    BOOLEAN                  NOT NULL DEFAULT FALSE,
    streak_days      INTEGER                  NOT NULL DEFAULT 0,
    last_activity_at TIMESTAMP WITH TIME ZONE,
    total_points     INTEGER                  NOT NULL DEFAULT 0,
    earned_this_year INTEGER                  NOT NULL DEFAULT 0,
    badges_json      TEXT                     NOT NULL DEFAULT '[]',
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
