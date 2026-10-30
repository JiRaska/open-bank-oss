-- ADR-0334 #12376: contribution-schedule changes and beneficiary designations on an existing
-- contract, each SCA-signed and kept as append-only history.
--
-- Rollback (no other object references these tables; contracts keep their current designation in
-- pension_contracts.beneficiaries and their originally agreed schedule):
--   DROP TABLE pension_beneficiary_designations; DROP TABLE pension_contribution_schedule_changes;
--   DROP SEQUENCE pension_beneficiary_designations_seq; DROP SEQUENCE pension_contribution_schedule_changes_seq;

-- One row per agreed schedule change. Rows are never deleted; the only update is
-- SCHEDULED -> SUPERSEDED when a later change replaces one that had not yet taken effect.
CREATE TABLE pension_contribution_schedule_changes (
    id                 BIGINT PRIMARY KEY,
    contract_id        UUID NOT NULL REFERENCES pension_contracts (contract_id),
    seq                INTEGER NOT NULL CHECK (seq >= 1),
    amount             NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    employer_amount    NUMERIC(19, 4) NOT NULL CHECK (employer_amount >= 0),
    currency           CHAR(3) NOT NULL,
    frequency          VARCHAR(16) NOT NULL CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'ANNUALLY')),
    day_of_month       SMALLINT NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    effective_from     DATE NOT NULL,
    status             VARCHAR(16) NOT NULL CHECK (status IN ('SCHEDULED', 'SUPERSEDED')),
    document_sha256    CHAR(64) NOT NULL,
    sca_challenge_id   VARCHAR(128) NOT NULL,
    idempotency_key    VARCHAR(256) NOT NULL,
    changed_at         TIMESTAMPTZ NOT NULL,
    -- Two concurrent changes compute the same next seq: exactly one insert wins, the other is a 409.
    CONSTRAINT uq_pension_schedule_changes_seq UNIQUE (contract_id, seq),
    CONSTRAINT uq_pension_schedule_changes_key UNIQUE (contract_id, idempotency_key)
);

-- One row per signed designation, in the participant's order. Append-only. Identity is minimal
-- (GDPR Art. 5(1)(c)): name and an optional known party id per beneficiary, nothing else.
CREATE TABLE pension_beneficiary_designations (
    id                 BIGINT PRIMARY KEY,
    contract_id        UUID NOT NULL REFERENCES pension_contracts (contract_id),
    seq                INTEGER NOT NULL CHECK (seq >= 1),
    beneficiaries      TEXT NOT NULL,
    document_sha256    CHAR(64) NOT NULL,
    sca_challenge_id   VARCHAR(128) NOT NULL,
    idempotency_key    VARCHAR(256) NOT NULL,
    changed_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_pension_beneficiary_designations_seq UNIQUE (contract_id, seq),
    CONSTRAINT uq_pension_beneficiary_designations_key UNIQUE (contract_id, idempotency_key)
);

CREATE SEQUENCE IF NOT EXISTS pension_contribution_schedule_changes_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_beneficiary_designations_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
