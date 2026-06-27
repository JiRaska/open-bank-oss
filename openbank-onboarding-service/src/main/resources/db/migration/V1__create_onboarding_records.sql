-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors.
-- Flyway migration V1: create onboarding read-model table (ADR-0068).
-- Rollback note: DROP TABLE onboarding_records;

CREATE TABLE IF NOT EXISTS onboarding_records (
    id             BIGSERIAL PRIMARY KEY,
    party_id       UUID        NOT NULL UNIQUE,
    legal_name     TEXT,
    email          TEXT,
    party_status   TEXT        NOT NULL,
    kyc_case_id    UUID,
    kyc_status     TEXT,
    sca_enrolled   BOOLEAN     NOT NULL DEFAULT FALSE,
    device_count   INTEGER     NOT NULL DEFAULT 0,
    funnel_stage   TEXT        NOT NULL,
    blocked_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_onboarding_funnel_stage ON onboarding_records (funnel_stage);
CREATE INDEX IF NOT EXISTS idx_onboarding_updated_at   ON onboarding_records (updated_at DESC);
