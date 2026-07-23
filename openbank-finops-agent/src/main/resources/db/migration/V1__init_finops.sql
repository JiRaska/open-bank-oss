-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0112 / ADR-0148: persisted FinOps cost anomalies + their HITL lifecycle. Gives the agent
-- cross-restart memory so a rejected proposal is not re-raised on the next 03:00 run.
-- Rollback: DROP TABLE anomalies;  (no dependents; the service degrades to no history on rollback)

CREATE TABLE anomalies (
    id                            UUID PRIMARY KEY,
    detector                      VARCHAR(40)  NOT NULL,
    severity                      VARCHAR(16)  NOT NULL,
    detected_at                   TIMESTAMPTZ  NOT NULL,
    title                         TEXT         NOT NULL,
    raw_metric_value              NUMERIC      NOT NULL,
    threshold                     NUMERIC      NOT NULL,
    affected_resource             VARCHAR(255) NOT NULL,
    root_cause                    TEXT,
    proposal_pr_url               VARCHAR(512),
    proposed_iac_diff             TEXT,
    estimated_monthly_saving_usd  NUMERIC,
    status                        VARCHAR(16)  NOT NULL,
    diagnosed_at                  TIMESTAMPTZ,
    proposed_at                   TIMESTAMPTZ
);

-- The HITL queue lists active anomalies ordered by recency.
CREATE INDEX idx_anomalies_status_detected_at ON anomalies (status, detected_at DESC);
