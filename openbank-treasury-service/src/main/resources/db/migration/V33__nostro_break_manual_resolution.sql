-- SPDX-License-Identifier: Apache-2.0
-- Four-eyes manual resolution of a nostro reconciliation break (ADR-0315 D7, #14770). Until now a
-- break resolved only when a later reconciliation MATCHED it, so a statement-side break (in scope
-- only for its own, immutable statement) could never close and kept the aged-break alert firing
-- after people had investigated it. Now one person PROPOSES closing an OPEN break with a reason
-- (the break stays open and keeps ageing), and a DIFFERENT person APPROVES (or rejects) it. Nothing
-- is ever posted.
--
-- resolution_kind says HOW a resolved break was resolved: MATCHED (a later reconciliation no longer
-- listed it) or MANUAL (approved by a second person). Existing resolved rows can only have been
-- matched, and are backfilled so. resolution_reason / proposed_by / proposed_at describe the pending
-- or approved proposal; resolved_by / resolved_at the approval. Four-eyes is enforced a second time
-- here (as deals_four_eyes): a MANUAL row names a proposer and a different approver. Every step
-- (PROPOSED, APPROVED, REJECTED) is appended to nostro_break_resolution_history, which refuses
-- UPDATE, DELETE and TRUNCATE, so no later code path can erase who proposed, approved or rejected
-- a closure and why.
--
-- Rollback: revert the feature, then (only under a separately reviewed plan, since the history is
-- the audit record of every manual closure):
--   DROP TABLE nostro_break_resolution_history;
--   DROP FUNCTION nostro_break_resolution_history_append_only();
--   ALTER TABLE nostro_breaks DROP CONSTRAINT ck_nostro_break_proposal,
--       DROP CONSTRAINT ck_nostro_break_resolution, DROP CONSTRAINT nostro_breaks_four_eyes,
--       DROP COLUMN resolution_kind, DROP COLUMN resolution_reason, DROP COLUMN resolved_by,
--       DROP COLUMN resolved_at, DROP COLUMN proposed_by, DROP COLUMN proposed_at,
--       DROP COLUMN version;
-- A MANUAL row left resolved by a rolled-back build stays resolved (resolved_on is set) — the old
-- code treats it as matched and reopens it only if a reconciliation lists it again.

ALTER TABLE nostro_breaks
    ADD COLUMN resolution_kind   VARCHAR(8) CHECK (resolution_kind IN ('MATCHED', 'MANUAL')),
    ADD COLUMN resolution_reason VARCHAR(500),
    ADD COLUMN resolved_by       VARCHAR(255),
    ADD COLUMN resolved_at       TIMESTAMPTZ,
    ADD COLUMN proposed_by       VARCHAR(255),
    ADD COLUMN proposed_at       TIMESTAMPTZ,
    -- Compare-and-set token: every write to an existing break (the sweep's match/reopen and each
    -- four-eyes step) is UPDATE ... WHERE version = <the version it was computed from>, so a write
    -- computed from a stale read matches no row instead of overwriting a newer state.
    ADD COLUMN version           BIGINT NOT NULL DEFAULT 0;

UPDATE nostro_breaks SET resolution_kind = 'MATCHED' WHERE resolved_on IS NOT NULL;

ALTER TABLE nostro_breaks
    ADD CONSTRAINT ck_nostro_break_resolution CHECK (
        ((resolved_on IS NULL) = (resolution_kind IS NULL))
        AND (COALESCE(resolution_kind = 'MANUAL', FALSE) = (resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
    ),
    -- A proposal names its proposer, time and reason together; a MANUAL resolution has one.
    ADD CONSTRAINT ck_nostro_break_proposal CHECK (
        (proposed_by IS NULL) = (proposed_at IS NULL)
        AND (proposed_by IS NULL) = (resolution_reason IS NULL)
        AND (resolution_kind IS DISTINCT FROM 'MANUAL' OR proposed_by IS NOT NULL)
    ),
    -- Four-eyes, a second time at the database (as deals_four_eyes).
    ADD CONSTRAINT nostro_breaks_four_eyes CHECK (resolved_by IS NULL OR resolved_by <> proposed_by);

CREATE TABLE nostro_break_resolution_history (
    id                  BIGSERIAL     PRIMARY KEY,
    break_uuid          UUID          NOT NULL REFERENCES nostro_breaks (break_uuid),
    step                VARCHAR(8)    NOT NULL CHECK (step IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    at                  TIMESTAMPTZ   NOT NULL,
    actor_id            VARCHAR(255)  NOT NULL,
    -- The proposal's reason (PROPOSED) or the rejection's (REJECTED); an approval adopts the proposal's.
    reason              VARCHAR(500)  CHECK ((step = 'APPROVED') = (reason IS NULL)),
    -- The client's Idempotency-Key of the step: a replay answers the stored outcome.
    command_key         VARCHAR(128)  NOT NULL
                        CONSTRAINT uq_nostro_break_resolution_history_command_key UNIQUE,
    -- sha256 of (account, break, step, actor, reason): a replay under command_key must match it.
    request_fingerprint CHAR(64)      NOT NULL
);

CREATE INDEX idx_nostro_break_resolution_history_break ON nostro_break_resolution_history (break_uuid, id);

CREATE FUNCTION nostro_break_resolution_history_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'nostro_break_resolution_history is append-only';
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER nostro_break_resolution_history_no_rewrite
    BEFORE UPDATE OR DELETE ON nostro_break_resolution_history
    FOR EACH ROW EXECUTE FUNCTION nostro_break_resolution_history_append_only();

CREATE TRIGGER nostro_break_resolution_history_no_truncate
    BEFORE TRUNCATE ON nostro_break_resolution_history
    FOR EACH STATEMENT EXECUTE FUNCTION nostro_break_resolution_history_append_only();
