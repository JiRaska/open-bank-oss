-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- #12425 / ADR-0336: the corporate register — the reporting company's figures no system in the
-- platform produces (capital and its requirement, share capital, headcount, qualifying holders,
-- governing bodies, dividends), feeding ČNB PSP 32-04, 50-04 and 40-01.
--
-- Versioned and effective-dated; append-only. A change is a new row. The ONLY update allowed is
-- the decision of a PROPOSED row (to APPROVED or REJECTED, by someone other than its proposer),
-- and the trigger below refuses anything else, so the rows are the audit trail of every figure.
--
-- Rollback: DROP TABLE corporate_register_entry; DROP FUNCTION corporate_register_guard();
-- (nothing references the table).
CREATE TABLE corporate_register_entry (
    id              UUID PRIMARY KEY,
    entity_id       VARCHAR(128)  NOT NULL,
    fact            VARCHAR(64)   NOT NULL,
    value           NUMERIC(24,6) NOT NULL CHECK (value >= 0),
    effective_from  DATE          NOT NULL,
    version         INTEGER       NOT NULL CHECK (version >= 1),
    reason          VARCHAR(1024) NOT NULL,
    evidence        VARCHAR(1024) NOT NULL,
    proposed_by     VARCHAR(255)  NOT NULL,
    proposed_at     TIMESTAMPTZ   NOT NULL,
    status          VARCHAR(16)   NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by      VARCHAR(255),
    decided_at      TIMESTAMPTZ,
    CONSTRAINT uq_corporate_register_version UNIQUE (entity_id, fact, effective_from, version),
    CONSTRAINT ck_corporate_register_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT ck_corporate_register_decided CHECK ((status = 'PROPOSED') = (decided_by IS NULL))
);

CREATE INDEX ix_corporate_register_entity ON corporate_register_entry (entity_id, fact, effective_from);

CREATE FUNCTION corporate_register_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'corporate_register_entry is append-only';
    END IF;
    IF OLD.status <> 'PROPOSED'
        OR NEW.id <> OLD.id OR NEW.entity_id <> OLD.entity_id OR NEW.fact <> OLD.fact
        OR NEW.value <> OLD.value OR NEW.effective_from <> OLD.effective_from OR NEW.version <> OLD.version
        OR NEW.reason <> OLD.reason OR NEW.evidence <> OLD.evidence
        OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_at <> OLD.proposed_at THEN
        RAISE EXCEPTION 'corporate_register_entry: only the decision of a PROPOSED entry may change';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_corporate_register_guard
    BEFORE UPDATE OR DELETE ON corporate_register_entry
    FOR EACH ROW EXECUTE FUNCTION corporate_register_guard();
