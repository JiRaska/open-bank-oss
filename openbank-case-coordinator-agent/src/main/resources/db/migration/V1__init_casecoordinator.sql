-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0244: persisted case workflow metadata and agent contribution records.
-- Rollback: DROP TABLE case_contribution; DROP TABLE case_workflow; (no dependents; the service degrades to no history on rollback)

CREATE TABLE case_workflow (
    id                 UUID PRIMARY KEY,
    case_class         VARCHAR(40)  NOT NULL,
    disposition_target VARCHAR(255) NOT NULL,
    opened_at          TIMESTAMPTZ  NOT NULL,
    deadline_at        TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    budget_tokens      INTEGER      NOT NULL,
    budget_contributions INTEGER    NOT NULL,
    contested_rate       NUMERIC(5,4) NOT NULL DEFAULT 0.0
);

CREATE TABLE case_contribution (
    id                 UUID PRIMARY KEY,
    case_id            UUID         NOT NULL REFERENCES case_workflow(id) ON DELETE CASCADE,
    agent_id           VARCHAR(64)  NOT NULL,
    contributed_at       TIMESTAMPTZ  NOT NULL,
    tokens_used        INTEGER      NOT NULL,
    proposal_id        VARCHAR(64),
    preemption_vote    VARCHAR(16)
);

CREATE INDEX idx_case_workflow_status ON case_workflow (status, opened_at DESC);
CREATE INDEX idx_case_contribution_case_id ON case_contribution (case_id);
