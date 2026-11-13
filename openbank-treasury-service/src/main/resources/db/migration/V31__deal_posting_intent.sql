-- SPDX-License-Identifier: Apache-2.0
-- #13883: intent-first ledger posting for settle, mature and reverse. A row is written BEFORE
-- treasury contacts the ledger, keyed by the journal's idempotency key, and completed in the same
-- transaction that records the deal's state change. An OPEN row is a journal the ledger may hold
-- that treasury has not applied: PostingIntentReconciler re-runs the command under the same key
-- (the ledger answers with the original journal) and the day-close reporter holds the intent's
-- value day until it is closed. REFUSED = the ledger's coded refusal of a first attempt (no journal
-- exists; a retry reopens it as a new attempt). CLOSED_BY_OPERATOR = a person released the hold; a
-- journal MAY exist, so a re-issue reopens it keeping its first value day.
-- Rollback: revert the feature and retain this additive table (DROP TABLE deal_posting_intent only
-- under a separately reviewed plan, and only once no row is OPEN: an open row is the only record of
-- a journal treasury may not have applied).
CREATE TABLE deal_posting_intent (
    idempotency_key VARCHAR(128)  PRIMARY KEY,
    deal_id         UUID          NOT NULL,
    event           VARCHAR(32)   NOT NULL,
    action          VARCHAR(16)   NOT NULL CHECK (action IN ('SETTLE', 'MATURE', 'REVERSE', 'ACCRUE')),
    value_date      DATE          NOT NULL,
    actor_id        VARCHAR(255)  NOT NULL,
    actor_type      VARCHAR(16)   NOT NULL,
    reason          TEXT,
    command_key     VARCHAR(128),
    status          VARCHAR(24)   NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN', 'COMPLETED', 'REFUSED', 'CLOSED_BY_OPERATOR')),
    attempts        INTEGER       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL,
    closed_at       TIMESTAMPTZ,
    -- The LATEST operator close (status CLOSED_BY_OPERATOR). Never cleared: a re-issue reopens the
    -- row and keeps them; every close is also in deal_posting_intent_history.
    closed_by       VARCHAR(255),
    close_reason    TEXT,
    -- Reconciler claim: a pass claims rows with FOR UPDATE SKIP LOCKED and leases them until
    -- claimed_until, so two pods never resume the same intent at once.
    claim_token     UUID,
    claimed_until   TIMESTAMPTZ,
    -- Set when the reconciler escalates the intent (too old to resume as its stored actor): it stays
    -- OPEN and holding its day, and is never claimed again, so it cannot starve resumable intents.
    escalated_at    TIMESTAMPTZ
);

CREATE INDEX idx_deal_posting_intent_open ON deal_posting_intent (value_date) WHERE status = 'OPEN';
CREATE INDEX idx_deal_posting_intent_claimable ON deal_posting_intent (created_at)
    WHERE status = 'OPEN' AND escalated_at IS NULL;

-- One OPEN lifecycle posting per deal (settlement, reversal, maturity or accrual day), enforced by
-- the database. A reversal that posts nothing (an unsettled deal) claims the slot too. An accrual
-- day records an ACCRUE intent before its ledger call, exactly as a reversal or maturity does, so
-- whichever opens second is refused (the reversal answers 409, the accrual defers). That is what
-- makes "a reversal offsets every accrual posted before it" hold under concurrency without holding
-- a lock across the ledger call: an accrual can no longer be in flight while a reversal is decided.
-- A settlement, reversal or maturity an OPERATOR closed keeps the slot too: its journal may be in the
-- ledger, so a reversal that would post nothing (behind a closed settlement), an accrual or another
-- lifecycle command must not proceed until it is re-issued (the re-issue reopens the same row, which
-- holds the slot already). An accrual day is never operator-closed. A journal applied after a close
-- completes the row and releases the slot.
CREATE UNIQUE INDEX uq_deal_posting_intent_one_open_per_deal ON deal_posting_intent (deal_id)
    WHERE status = 'OPEN' OR (status = 'CLOSED_BY_OPERATOR' AND action IN ('SETTLE', 'REVERSE', 'MATURE'));

-- Append-only history of every intent transition (opened, reopened, refused, closed by an operator,
-- completed): who, when, why. Rows are written in the same statement as the transition they record
-- (a data-modifying CTE), and the triggers below refuse UPDATE, DELETE and TRUNCATE, so a reopen or a
-- later close by the APPLICATION can never erase an earlier operator decision. They do not bind the
-- table's owner, who can disable a trigger: this protects the record from the service's own code
-- paths, not from a database administrator (that is the database's access control and its audit).
-- Rollback: as for deal_posting_intent; drop the trigger and function with the table.
CREATE TABLE deal_posting_intent_history (
    id              BIGSERIAL     PRIMARY KEY,
    idempotency_key VARCHAR(128)  NOT NULL,
    at              TIMESTAMPTZ   NOT NULL,
    transition      VARCHAR(32)   NOT NULL CHECK (transition IN
                    ('OPENED', 'REOPENED', 'REOPENED_AFTER_OPERATOR_CLOSE', 'REFUSED', 'CLOSED_BY_OPERATOR',
                     'COMPLETED', 'JOURNAL_APPLIED_AFTER_CLOSE')),
    actor_id        VARCHAR(255),
    reason          TEXT,
    -- JOURNAL_APPLIED_AFTER_CLOSE only: the status the intent was closed with (REFUSED or
    -- CLOSED_BY_OPERATOR), which the late journal did NOT overwrite.
    prior_status    VARCHAR(24),
    -- CLOSED_BY_OPERATOR only: the client's Idempotency-Key of the close (a replay answers CLOSED).
    command_key     VARCHAR(128)  CONSTRAINT uq_deal_posting_intent_history_command_key UNIQUE,
    -- sha256 of (deal, event, actor, reason) of that close: a replay under command_key must match it.
    request_fingerprint CHAR(64),
    value_date      DATE          NOT NULL
);

CREATE INDEX idx_deal_posting_intent_history_key ON deal_posting_intent_history (idempotency_key, id);

CREATE FUNCTION deal_posting_intent_history_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'deal_posting_intent_history is append-only';
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER deal_posting_intent_history_no_rewrite
    BEFORE UPDATE OR DELETE ON deal_posting_intent_history
    FOR EACH ROW EXECUTE FUNCTION deal_posting_intent_history_append_only();

CREATE TRIGGER deal_posting_intent_history_no_truncate
    BEFORE TRUNCATE ON deal_posting_intent_history
    FOR EACH STATEMENT EXECUTE FUNCTION deal_posting_intent_history_append_only();
