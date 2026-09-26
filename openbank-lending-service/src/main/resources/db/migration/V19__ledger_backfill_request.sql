-- SPDX-License-Identifier: Apache-2.0
-- Four-eyes record of the one-off ledger backfill (#10746). Loans disbursed while lending's real
-- GL adapter was compiled out of the image (#6057) have no ledger history; the backfill re-posts
-- it through the ledger REST API with the live idempotency references. This table holds WHO asked,
-- WHO approved (must differ), WHICH plan (plan_hash binds the approval to the exact journal set),
-- and what each execution achieved. It never holds money: the journals live in ledger-service.
--
-- Rollback: DROP TABLE ledger_backfill_request; (no other object references it)

CREATE TABLE ledger_backfill_request (
    id              uuid         PRIMARY KEY,
    state           VARCHAR(16)  NOT NULL,
    cutover_date    DATE         NOT NULL,
    plan_hash       VARCHAR(64)  NOT NULL,
    loan_count      INT          NOT NULL,
    leg_count       INT          NOT NULL,
    proposed_by     VARCHAR(128) NOT NULL,
    proposed_at     TIMESTAMPTZ  NOT NULL,
    decided_by      VARCHAR(128),
    decided_at      TIMESTAMPTZ,
    decision_reason VARCHAR(512),
    executed_by     VARCHAR(128),
    executed_at     TIMESTAMPTZ,
    last_result     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_backfill_request_state ON ledger_backfill_request(state);
