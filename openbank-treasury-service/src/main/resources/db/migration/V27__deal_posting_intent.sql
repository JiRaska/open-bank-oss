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
    action          VARCHAR(16)   NOT NULL CHECK (action IN ('SETTLE', 'MATURE', 'REVERSE')),
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

-- Append-only history of every intent transition (opened, reopened, refused, closed by an operator,
-- completed): who, when, why. Rows are written in the same statement as the transition they record
-- (a data-modifying CTE), and the trigger below refuses any UPDATE or DELETE, so a reopen or a later
-- close can never erase an earlier operator decision.
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
