-- Supervisory return submission lifecycle (ADR-0097 follow-up).
-- Rollback: finrep had no database before this migration; rolling back means dropping both
-- tables (DROP TABLE finrep_submission_attempt; DROP TABLE finrep_submission;) — no other data.
CREATE TABLE finrep_submission (
    id                          UUID PRIMARY KEY,
    template_id                 VARCHAR(32)  NOT NULL,
    reference_period            DATE         NOT NULL,
    revision                    INTEGER      NOT NULL CHECK (revision >= 1),
    state                       VARCHAR(16)  NOT NULL
        CHECK (state IN ('DRAFT', 'PACKAGED', 'SUBMITTING', 'OUTCOME_UNKNOWN', 'SUBMITTED', 'ACKNOWLEDGED', 'REJECTED')),
    created_by                  VARCHAR(255) NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL,
    reporting_framework_version VARCHAR(32),
    taxonomy_version            VARCHAR(32),
    package_hash                CHAR(64),
    packaged_by                 VARCHAR(255),
    packaged_at                 TIMESTAMPTZ,
    submitted_at                TIMESTAMPTZ,
    transport_reference         VARCHAR(255),
    ack_accepted                BOOLEAN,
    ack_reference               VARCHAR(255),
    ack_message                 VARCHAR(2000),
    ack_recorded_by             VARCHAR(255),
    ack_recorded_at             TIMESTAMPTZ,
    -- Set while a request holds the PACKAGED -> SUBMITTING claim; drives the claim lease.
    claimed_at                  TIMESTAMPTZ,
    CONSTRAINT uq_finrep_submission_key UNIQUE (template_id, reference_period, revision)
);

CREATE TABLE finrep_submission_attempt (
    id                  UUID PRIMARY KEY,
    submission_id       UUID         NOT NULL REFERENCES finrep_submission (id),
    attempt_no          INTEGER      NOT NULL,
    transport           VARCHAR(64)  NOT NULL,
    outcome             VARCHAR(16)  NOT NULL CHECK (outcome IN ('SUBMITTED', 'DRY_RUN', 'REJECTED', 'FAILED', 'UNKNOWN')),
    transport_reference VARCHAR(255),
    detail              VARCHAR(2000),
    attempted_by        VARCHAR(255) NOT NULL,
    attempted_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_finrep_submission_attempt_no UNIQUE (submission_id, attempt_no)
);
