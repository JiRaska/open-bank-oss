-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0167: persisted authorization-policy-drift findings + their HITL lifecycle.
-- Rollback: DROP TABLE findings;  (no dependents; the service degrades to no history on rollback)

CREATE TABLE findings (
    id                UUID PRIMARY KEY,
    -- AuthzPolicyCheckType values are long (UNREACHABLE_PRINCIPAL_TYPE_RULE is 31 chars); sized with
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
