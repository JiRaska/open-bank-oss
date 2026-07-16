-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0168: persisted silent-test-failure findings + their HITL lifecycle.
-- Rollback: DROP TABLE findings;  (no dependents; the service degrades to no history on rollback)

CREATE TABLE findings (
    id                UUID PRIMARY KEY,
    -- FlakyTestCheckType values are long (PACT_LOCAL_VERIFICATION_BLIND_SPOT is 35 chars); sized with
    -- headroom for future check types rather than trimmed to today's longest.
    check_type        VARCHAR(48)  NOT NULL,
    severity          VARCHAR(16)  NOT NULL,
    detected_at       TIMESTAMPTZ  NOT NULL,
    title             TEXT         NOT NULL,
    component         VARCHAR(255) NOT NULL,
    file_path         VARCHAR(512) NOT NULL,
    raw_metric_value  NUMERIC      NOT NULL,
    threshold         NUMERIC      NOT NULL,
    root_cause        TEXT,
    proposal_url      VARCHAR(512),
    proposed_fix_diff TEXT,
    status            VARCHAR(16)  NOT NULL,
    diagnosed_at      TIMESTAMPTZ,
    proposed_at       TIMESTAMPTZ
);

-- The dashboard/HITL queue lists active findings ordered by recency.
CREATE INDEX idx_findings_status_detected_at ON findings (status, detected_at DESC);
