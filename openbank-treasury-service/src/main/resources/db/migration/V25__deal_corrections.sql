-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D7: four-eyes corrections of a booked deal. A correction is proposed (PENDING) with a
-- preview of the flows and delta journals it implies, approved by a different human who is not a
-- term author (APPROVED: the new deal version and its schedule commit in that transaction, the
-- delta journals are owed), and POSTED once every delta journal is in the ledger. At most one
-- PENDING correction per deal.
--
-- Rollback (no correction ever approved): DROP TABLE deal_corrections;
-- otherwise the deal versions it created stay and only the audit of their cause is lost.
CREATE TABLE deal_corrections (
    correction_id          UUID          PRIMARY KEY,
    deal_id                UUID          NOT NULL REFERENCES deals (deal_id),
    base_version           INT           NOT NULL CHECK (base_version >= 1),
    base_schedule_version  INT           NOT NULL CHECK (base_schedule_version >= 0),
    status                 VARCHAR(12)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'POSTED', 'REJECTED')),
    terms_json             TEXT          NOT NULL,
    full_unwind            BOOLEAN       NOT NULL,
    reason                 VARCHAR(500)  NOT NULL,
    effective_from         DATE          NOT NULL,
    proposed_by            VARCHAR(255)  NOT NULL,
    proposed_by_type       VARCHAR(16)   NOT NULL,
    proposed_at            TIMESTAMPTZ   NOT NULL,
    decided_by             VARCHAR(255),
    decided_by_type        VARCHAR(16),
    decided_at             TIMESTAMPTZ,
    decision_reason        VARCHAR(500),
    new_version            INT,
    preview_json           TEXT          NOT NULL,
    deltas_json            TEXT,
    posted_json            TEXT,
    -- A counterparty-limit breach the corrected terms cause: requested by the proposer, overridden
    -- (bounded to the exposure measured) by a senior who is no term author (ADR-0315 D4).
    limit_override_requested BOOLEAN     NOT NULL DEFAULT FALSE,
    limit_override_by      VARCHAR(255),
    limit_override_reason  VARCHAR(500),
    limit_override_at      TIMESTAMPTZ,
    limit_override_covers  NUMERIC(20,2),
    limit_override_limit   NUMERIC(20,2),
    row_version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT deal_corrections_decided CHECK (status = 'PENDING' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT deal_corrections_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT deal_corrections_override_not_proposer CHECK (limit_override_by IS NULL OR limit_override_by <> proposed_by),
    CONSTRAINT deal_corrections_human_decider CHECK (decided_by_type IS NULL OR decided_by_type = 'HUMAN')
);
CREATE INDEX idx_deal_corrections_deal ON deal_corrections (deal_id);
CREATE INDEX idx_deal_corrections_status ON deal_corrections (status, proposed_at);
CREATE UNIQUE INDEX uq_deal_corrections_one_pending ON deal_corrections (deal_id) WHERE status = 'PENDING';
