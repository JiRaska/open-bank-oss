-- SPDX-License-Identifier: Apache-2.0
-- Four-eyes record of voiding the synthetic loans a ledger backfill posted (#10969). The 44 loans
-- back-posted by #10746 were never paid out, so they are cancelled rather than disbursed: every
-- ledger leg of each loan is offset by a mirror journal (net GL effect zero) and the loan leaves
-- the book as UNWOUND. A void always names the EXECUTED backfill request it undoes, so it can
-- only ever reach loans that request posted. plan_hash binds the approval to the exact journal
-- set, exactly as V19 does. It never holds money: the journals live in ledger-service.
--
-- Rollback: DROP TABLE ledger_backfill_void_request; (no other object references it)

CREATE TABLE ledger_backfill_void_request (
    id                  uuid         PRIMARY KEY,
    source_request_id   uuid         NOT NULL,
    state               VARCHAR(16)  NOT NULL,
    void_date           DATE         NOT NULL,
    plan_hash           VARCHAR(64)  NOT NULL,
    loan_count          INT          NOT NULL,
    leg_count           INT          NOT NULL,
    proposed_by         VARCHAR(128) NOT NULL,
    proposed_at         TIMESTAMPTZ  NOT NULL,
    decided_by          VARCHAR(128),
    decided_at          TIMESTAMPTZ,
    decision_reason     VARCHAR(512),
    executed_by         VARCHAR(128),
    executed_at         TIMESTAMPTZ,
    last_result         TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_backfill_void_request_source ON ledger_backfill_void_request(source_request_id);
CREATE INDEX idx_ledger_backfill_void_request_state ON ledger_backfill_void_request(state);
