-- SPDX-License-Identifier: Apache-2.0
-- ADR-0333 D3: a resumable, bounded audience admission run.
-- Rollback: disable new run creation, let active runs finish or halt them, then drop these
-- tables after their evidence has been retained under the campaign retention policy.
CREATE TABLE campaign_bulk_runs (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaigns (id),
    state TEXT NOT NULL CHECK (state IN ('RUNNING', 'HELD', 'COMPLETED')),
    cursor_party_id UUID,
    page_size INT NOT NULL CHECK (page_size BETWEEN 1 AND 500),
    admitted BIGINT NOT NULL DEFAULT 0,
    failures BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    lease_owner UUID,
    last_error TEXT,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_campaign_bulk_live ON campaign_bulk_runs (campaign_id)
    WHERE state IN ('RUNNING', 'HELD');
CREATE INDEX idx_campaign_bulk_ready ON campaign_bulk_runs (state, updated_at)
    WHERE state = 'RUNNING';

-- The single row serialises admission across all campaigns and all service replicas.
-- Configuration may set page_size only after a measured capacity exercise; there is no
-- runtime API that can raise the fleet budget.
CREATE TABLE campaign_admission_budget (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    next_available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    lease_owner UUID
);
INSERT INTO campaign_admission_budget (id, next_available_at)
VALUES (1, '-infinity'::timestamptz);
