-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0119: persisted DevOps findings + their HITL lifecycle.
-- Rollback: DROP TABLE findings;  (no dependents; the service degrades to no history on rollback)

CREATE TABLE findings (
    id                   UUID PRIMARY KEY,
    detector             VARCHAR(40)  NOT NULL,
    severity             VARCHAR(16)  NOT NULL,
    detected_at          TIMESTAMPTZ  NOT NULL,
    title                TEXT         NOT NULL,
    raw_metric_value     NUMERIC      NOT NULL,
    threshold            NUMERIC      NOT NULL,
    affected_resource    VARCHAR(255) NOT NULL,
    dora_metric_impacted VARCHAR(40),
    root_cause           TEXT,
    remediation_kind     VARCHAR(24)  NOT NULL,
    proposal_pr_url      VARCHAR(512),
    proposed_remediation TEXT,
    status               VARCHAR(16)  NOT NULL,
    diagnosed_at         TIMESTAMPTZ,
    proposed_at          TIMESTAMPTZ
);

-- The dashboard/HITL queue lists active findings ordered by recency.
CREATE INDEX idx_findings_status_detected_at ON findings (status, detected_at DESC);
