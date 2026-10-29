-- SPDX-License-Identifier: Apache-2.0
-- #13883: intent-first ledger posting for settle, mature and reverse. A row is written BEFORE
-- treasury contacts the ledger, keyed by the journal's idempotency key, and completed in the same
-- transaction that records the deal's state change. An OPEN row is a journal the ledger may hold
-- that treasury has not applied: PostingIntentReconciler re-runs the command under the same key
-- (the ledger answers with the original journal) and the day-close reporter holds the intent's
-- value day until it is closed. REFUSED = the ledger's coded refusal of a first attempt (no journal),
-- or an operator's audited close (closed_by, last_error = the reason).
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
    status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED', 'REFUSED')),
    attempts        INTEGER       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL,
    closed_at       TIMESTAMPTZ,
    -- An operator's close of an intent that cannot complete (REFUSED by a person, audited here).
    closed_by       VARCHAR(255),
    -- Reconciler claim: a pass claims rows with FOR UPDATE SKIP LOCKED and leases them until
    -- claimed_until, so two pods never resume the same intent at once.
    claim_token     UUID,
    claimed_until   TIMESTAMPTZ
);

CREATE INDEX idx_deal_posting_intent_open ON deal_posting_intent (value_date) WHERE status = 'OPEN';
