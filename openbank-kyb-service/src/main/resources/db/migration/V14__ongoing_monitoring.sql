-- Ongoing sanctions / PEP monitoring of approved business cases (ADR-0376, kyb side).
--
-- 1. kyb_cases.monitoring_review_from / monitoring_review_opened_at: set while an approved case is
--    back under review because the monitored-subject registry reported a match. `from` is the
--    status the case held when the first finding arrived and is what the applicant keeps reading.
--    Additive and nullable: every existing case reads back with no monitoring review.
--
-- 2. kyb_monitored_subjects: the DESIRED registration state of each screened subject of an
--    approved case, and whether sanctions-service has been told. A row is written in the same
--    transaction as the case write that changes who must be monitored; a scheduled relay then
--    sends PUT / DELETE to the registry and retries until it is accepted. `generation` moves on
--    every change of `desired`, so an answer to a superseded request cannot mark a newer wish as
--    delivered.
--
--    The table holds NO personal data: `subject_ref` is the opaque reference the subject is
--    registered under (a random UUID, or the entity's party id), and the name the registry needs
--    is read from the case at send time. `last_error` is a short machine reason, never a body.
--
-- Rollback: DROP TABLE kyb_monitored_subjects; DROP SEQUENCE kyb_monitored_subjects_seq;
--           ALTER TABLE kyb_cases DROP COLUMN monitoring_review_from,
--                                 DROP COLUMN monitoring_review_opened_at;
-- Roll the application back first. Dropping the table forgets which subjects were registered:
-- the registry keeps monitoring them until somebody withdraws them, and their findings would
-- arrive at a release that no longer knows the references. Before dropping, withdraw every row
-- whose desired state is REGISTERED (DELETE /api/v3/sanctions/monitored-subjects/{subject_ref}).
-- A case under a monitoring review at rollback time stays in MANUAL_REVIEW and is resolved or
-- rejected by an operator as any other review.
ALTER TABLE kyb_cases ADD COLUMN monitoring_review_from VARCHAR(32);
ALTER TABLE kyb_cases ADD COLUMN monitoring_review_opened_at TIMESTAMPTZ;

CREATE TABLE kyb_monitored_subjects (
    id              BIGSERIAL    PRIMARY KEY,
    case_id         UUID         NOT NULL,
    subject_ref     VARCHAR(255) NOT NULL,
    desired         VARCHAR(16)  NOT NULL,
    sync_state      VARCHAR(16)  NOT NULL,
    generation      BIGINT       NOT NULL DEFAULT 0,
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    pending_since   TIMESTAMPTZ,
    synced_at       TIMESTAMPTZ,
    last_error      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT kyb_monitored_subjects_desired_ck CHECK (desired IN ('REGISTERED', 'WITHDRAWN')),
    CONSTRAINT kyb_monitored_subjects_state_ck CHECK (sync_state IN ('PENDING', 'SYNCED')),
    CONSTRAINT kyb_monitored_subjects_case_ref_uq UNIQUE (case_id, subject_ref)
);

-- The relay's work list: rows that still have to be sent, oldest due first.
CREATE INDEX kyb_monitored_subjects_due_idx
    ON kyb_monitored_subjects (next_attempt_at) WHERE sync_state = 'PENDING';
-- A finding names the subject by reference only.
CREATE INDEX kyb_monitored_subjects_ref_idx ON kyb_monitored_subjects (subject_ref);
-- The periodic re-assertion: registered rows by when the registry last confirmed them.
CREATE INDEX kyb_monitored_subjects_reassert_idx
    ON kyb_monitored_subjects (synced_at) WHERE desired = 'REGISTERED' AND sync_state = 'SYNCED';

-- Hibernate Reactive + PanacheEntity allocate ids from "<table>_seq" (allocationSize 50), as V1
-- and V4 do; without it every insert dies on a missing relation.
CREATE SEQUENCE IF NOT EXISTS kyb_monitored_subjects_seq INCREMENT BY 50;
GRANT ALL ON TABLE kyb_monitored_subjects TO openbank;
GRANT ALL ON SEQUENCE kyb_monitored_subjects_seq TO openbank;
