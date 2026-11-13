-- Ongoing sanctions / PEP monitoring of approved business cases (ADR-0376, kyb side).
--
-- Everything monitoring needs to keep lives in tables and columns that ONLY the release knowing
-- about monitoring writes. kyb_cases.screening_json is rewritten whole by every release on every
-- case write, and an older one drops what it does not know; nothing below is kept there.
--
-- 1. kyb_monitored_subjects: the DESIRED registration state of each screened subject of an
--    approved case, the reference it is registered under, and whether sanctions-service has been
--    told. A row is written in the same transaction as the case write that changes who must be
--    monitored; a scheduled relay then sends PUT / DELETE to the registry.
--      subject_key  the subject within the case, as kyb_cases.screening_json already stores it.
--                   It is a truncated hash of the subject's normalised name: NOT a name, but
--                   derived from one. It never leaves this database.
--      subject_ref  random, minted here when the row is created (gen_random_uuid), never
--                   derived from anything. It is the ONLY identifier of the subject that the
--                   registry, its URL and the findings topic carry. Unique: one reference is
--                   one subject of one case.
--      generation   moves on every change of `desired`.
--      claimed_until the lease of the relay delivering the row; a second relay leaves it alone.
--      last_error   a short machine reason, never a response body.
--
-- 2. kyb_monitoring_findings: one row per finding the registry reported, with the operator's
--    decision about THAT finding. No name: the list entry it matched and the subject key.
--
-- 3. kyb_cases gains what queries and alerts read without opening the findings:
--      monitoring_finding_count      how many findings the case has (0 = nothing to load)
--      monitoring_review_since       since when a finding has been waiting for an operator
--      monitoring_exit_owed_since    since when a confirmed sanctions match requires an exit
--      monitoring_exit_completed_*   who recorded that the exit was carried out, when, on what evidence
--      monitoring_synced_version     the row `version` at which the store was last brought up to
--                                    date. A release that does not know the store bumps `version`
--                                    and not this, which is how its writes are found and repaired.
--    None of it changes the case's status, which is what the applicant and every consumer read.
--    All additive and nullable (or defaulted): existing cases read back with no monitoring.
--
-- Rollback: DROP TABLE kyb_monitoring_findings; DROP TABLE kyb_monitored_subjects;
--           DROP SEQUENCE kyb_monitored_subjects_seq;
--           ALTER TABLE kyb_cases DROP COLUMN monitoring_finding_count,
--                                 DROP COLUMN monitoring_review_since,
--                                 DROP COLUMN monitoring_exit_owed_since,
--                                 DROP COLUMN monitoring_exit_completed_at,
--                                 DROP COLUMN monitoring_exit_completed_by,
--                                 DROP COLUMN monitoring_exit_evidence,
--                                 DROP COLUMN monitoring_synced_version;
-- Rolling the APPLICATION back needs none of that: the previous release ignores these tables and
-- columns and loses nothing in them. Dropping them forgets which subjects were registered and
-- under which reference: the registry keeps monitoring them and their findings would reach a
-- release that cannot resolve the reference. Before dropping, withdraw every row whose desired
-- state is REGISTERED (DELETE /api/v3/sanctions/monitored-subjects/{subject_ref}) and decide
-- every open finding.
CREATE TABLE kyb_monitored_subjects (
    id              BIGINT       PRIMARY KEY,
    case_id         UUID         NOT NULL,
    subject_key     VARCHAR(64)  NOT NULL,
    subject_ref     VARCHAR(64)  NOT NULL DEFAULT gen_random_uuid()::text,
    desired         VARCHAR(16)  NOT NULL DEFAULT 'REGISTERED',
    sync_state      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    generation      BIGINT       NOT NULL DEFAULT 0,
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    pending_since   TIMESTAMPTZ,
    synced_at       TIMESTAMPTZ,
    claimed_until   TIMESTAMPTZ,
    last_error      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT kyb_monitored_subjects_desired_ck CHECK (desired IN ('REGISTERED', 'WITHDRAWN')),
    CONSTRAINT kyb_monitored_subjects_state_ck CHECK (sync_state IN ('PENDING', 'SYNCED', 'REFUSED')),
    CONSTRAINT kyb_monitored_subjects_case_key_uq UNIQUE (case_id, subject_key),
    CONSTRAINT kyb_monitored_subjects_ref_uq UNIQUE (subject_ref)
);

-- The relay's work list: rows that still have to be sent, oldest due first.
CREATE INDEX kyb_monitored_subjects_due_idx
    ON kyb_monitored_subjects (next_attempt_at, id) WHERE sync_state = 'PENDING';
-- The periodic re-assertion: settled rows by when they were last settled.
CREATE INDEX kyb_monitored_subjects_reassert_idx
    ON kyb_monitored_subjects (updated_at, id) WHERE desired = 'REGISTERED' AND sync_state <> 'PENDING';

CREATE SEQUENCE IF NOT EXISTS kyb_monitored_subjects_seq;

CREATE TABLE kyb_monitoring_findings (
    finding_id         UUID         PRIMARY KEY,
    case_id            UUID         NOT NULL,
    subject_key        VARCHAR(64)  NOT NULL,
    match_kind         VARCHAR(64)  NOT NULL,
    list_type          VARCHAR(64)  NOT NULL,
    list_entry_ref     TEXT         NOT NULL,
    list_entry_version TEXT         NOT NULL,
    match_score        DOUBLE PRECISION NOT NULL,
    match_status       VARCHAR(64)  NOT NULL,
    detection          VARCHAR(64)  NOT NULL,
    received_at        TIMESTAMPTZ  NOT NULL,
    disposition        VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    decided_by         TEXT,
    decision_reason    TEXT,
    decided_at         TIMESTAMPTZ,
    CONSTRAINT kyb_monitoring_findings_disposition_ck
        CHECK (disposition IN ('OPEN', 'CLEARED_FALSE_POSITIVE', 'CONFIRMED'))
);
CREATE INDEX kyb_monitoring_findings_case_idx ON kyb_monitoring_findings (case_id);

ALTER TABLE kyb_cases ADD COLUMN monitoring_finding_count INT NOT NULL DEFAULT 0;
ALTER TABLE kyb_cases ADD COLUMN monitoring_review_since TIMESTAMPTZ;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_owed_since TIMESTAMPTZ;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_completed_at TIMESTAMPTZ;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_completed_by TEXT;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_evidence TEXT;
ALTER TABLE kyb_cases ADD COLUMN monitoring_synced_version BIGINT;

-- The cases the relay may have to write again: last written by a release that does not
-- synchronise. The query narrows this to approved cases and cases that have rows.
CREATE INDEX kyb_cases_monitoring_unreconciled_idx ON kyb_cases (id)
    WHERE monitoring_synced_version IS DISTINCT FROM version;
-- The operators' queue of monitoring holds.
CREATE INDEX kyb_cases_monitoring_hold_idx ON kyb_cases (id)
    WHERE monitoring_review_since IS NOT NULL OR monitoring_exit_owed_since IS NOT NULL;

GRANT ALL ON TABLE kyb_monitored_subjects TO openbank;
GRANT ALL ON TABLE kyb_monitoring_findings TO openbank;
GRANT ALL ON SEQUENCE kyb_monitored_subjects_seq TO openbank;
