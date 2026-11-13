-- Ongoing sanctions / PEP monitoring of approved business cases (ADR-0376, kyb side).
--
-- 1. kyb_cases.monitoring_review_opened_at / monitoring_exit_required_at / monitoring_exit_reason:
--    a monitoring finding on an APPROVED case awaits an operator (opened_at set), and, if the
--    operator decided the relationship has to end, since when and why (operators only). The
--    case's status is deliberately NOT changed by either: it is what the applicant and every
--    consumer read. Additive and nullable: every existing case reads back with no review.
--
-- 2. kyb_monitored_subjects: the DESIRED registration state of each screened subject of an
--    approved case, and whether sanctions-service has been told. A row is written in the same
--    transaction as the case write that changes who must be monitored; a scheduled relay then
--    sends PUT / DELETE to the registry and retries until it is accepted, or until the registry
--    refuses the subject itself (REFUSED: not retried blindly, counted and shown to an operator). `generation` moves on
--    every change of `desired`, so an answer to a superseded request cannot mark a newer wish as
--    delivered.
--
--    The table holds NO personal data: `subject_ref` is the opaque reference the subject is
--    registered under (a random UUID, or the entity's party id), and the name the registry needs
--    is read from the case at send time. `last_error` is a short machine reason, never a body.
--
-- Rollback: DROP TABLE kyb_monitored_subjects; DROP SEQUENCE kyb_monitored_subjects_seq;
--           ALTER TABLE kyb_cases DROP COLUMN monitoring_review_opened_at,
--                                 DROP COLUMN monitoring_exit_required_at,
--                                 DROP COLUMN monitoring_exit_reason;
-- Roll the application back first. Dropping the table forgets which subjects were registered:
-- the registry keeps monitoring them until somebody withdraws them, and their findings would
-- arrive at a release that no longer knows the references. Before dropping, withdraw every row
-- whose desired state is REGISTERED (DELETE /api/v3/sanctions/monitored-subjects/{subject_ref}).
-- A monitoring review open at rollback time is lost with its columns; the findings themselves stay
-- in kyb_cases.screening_json with an OPEN disposition, which the previous release cannot decide on
-- an approved case. Decide open reviews before rolling back.
ALTER TABLE kyb_cases ADD COLUMN monitoring_review_opened_at TIMESTAMPTZ;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_required_at TIMESTAMPTZ;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_reason TEXT;

CREATE TABLE kyb_monitored_subjects (
    id              BIGINT       PRIMARY KEY,
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
    CONSTRAINT kyb_monitored_subjects_state_ck CHECK (sync_state IN ('PENDING', 'SYNCED', 'REFUSED')),
    CONSTRAINT kyb_monitored_subjects_case_ref_uq UNIQUE (case_id, subject_ref)
);

-- The relay's work list: rows that still have to be sent, oldest due first.
CREATE INDEX kyb_monitored_subjects_due_idx
    ON kyb_monitored_subjects (next_attempt_at) WHERE sync_state = 'PENDING';
-- A finding names the subject by reference only.
CREATE INDEX kyb_monitored_subjects_ref_idx ON kyb_monitored_subjects (subject_ref);
-- The periodic re-assertion: settled rows by when they were last settled.
CREATE INDEX kyb_monitored_subjects_reassert_idx
    ON kyb_monitored_subjects (updated_at) WHERE desired = 'REGISTERED' AND sync_state <> 'PENDING';

-- Ids come from this sequence in the insert itself; the table has no entity and no second sequence.
CREATE SEQUENCE IF NOT EXISTS kyb_monitored_subjects_seq;
GRANT ALL ON TABLE kyb_monitored_subjects TO openbank;
GRANT ALL ON SEQUENCE kyb_monitored_subjects_seq TO openbank;
