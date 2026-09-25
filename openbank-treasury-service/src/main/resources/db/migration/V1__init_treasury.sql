-- SPDX-License-Identifier: Apache-2.0
-- ADR-0315: money-market deals with four-eyes booking.
-- Rollback (fresh environment only — nothing references these tables from outside):
--   DROP TABLE treasury_outbox, deal_commands, deal_journals, deal_transitions, deals, counterparties;
--   DROP SEQUENCE counterparties_seq, deals_seq, deal_transitions_seq, deal_journals_seq, deal_commands_seq, treasury_outbox_seq;

CREATE TABLE counterparties (
    id               BIGINT PRIMARY KEY,
    counterparty_id  VARCHAR(32)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    kind             VARCHAR(20)  NOT NULL CHECK (kind IN ('BANK', 'CENTRAL_BANK')),
    -- Per-currency credit limit on PLACED principal. NULL = no line in that currency (a deal
    -- there always breaches: limit 0).
    limit_czk        NUMERIC(20,2),
    limit_eur        NUMERIC(20,2),
    synthetic        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE deals (
    id                     BIGINT PRIMARY KEY,
    deal_id                UUID          NOT NULL UNIQUE,
    product                VARCHAR(32)   NOT NULL
        CHECK (product IN ('MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY')),
    counterparty_id        VARCHAR(32)   NOT NULL REFERENCES counterparties (counterparty_id),
    currency               CHAR(3)       NOT NULL CHECK (currency IN ('CZK', 'EUR')),
    principal              NUMERIC(20,2) NOT NULL CHECK (principal > 0),
    rate                   NUMERIC(9,6)  NOT NULL CHECK (rate >= 0),
    trade_date             DATE          NOT NULL,
    value_date             DATE          NOT NULL,
    maturity_date          DATE          NOT NULL,
    state                  VARCHAR(20)   NOT NULL
        CHECK (state IN ('DRAFT','PENDING_APPROVAL','BOOKED','SETTLED','MATURED','CANCELLED','REVERSED')),
    created_by             VARCHAR(255)  NOT NULL,
    created_by_type        VARCHAR(20)   NOT NULL,
    submitted_by           VARCHAR(255),
    submitted_by_type      VARCHAR(20),
    approved_by            VARCHAR(255),
    approved_by_type       VARCHAR(20),
    limit_amount           NUMERIC(20,2),
    limit_exposure_before  NUMERIC(20,2),
    limit_deal_amount      NUMERIC(20,2),
    rationale              TEXT,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    CONSTRAINT deals_dates_ordered CHECK (value_date >= trade_date AND maturity_date > value_date),
    -- Four-eyes, a second time at the database: whatever the application does, a row can never
    -- record the same principal as creator and approver.
    CONSTRAINT deals_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    -- An approval is always a human's (ADR-0315 D3).
    CONSTRAINT deals_approver_human CHECK (approved_by_type IS NULL OR approved_by_type = 'HUMAN')
);

CREATE INDEX idx_deals_state ON deals (state, created_at DESC);
CREATE INDEX idx_deals_counterparty ON deals (counterparty_id, currency, state);

CREATE TABLE deal_transitions (
    id          BIGINT PRIMARY KEY,
    deal_id     UUID         NOT NULL REFERENCES deals (deal_id),
    seq         INT          NOT NULL,
    from_state  VARCHAR(20),
    to_state    VARCHAR(20)  NOT NULL,
    actor_id    VARCHAR(255) NOT NULL,
    actor_type  VARCHAR(20)  NOT NULL,
    at          TIMESTAMPTZ  NOT NULL,
    note        TEXT,
    CONSTRAINT deal_transitions_seq_unique UNIQUE (deal_id, seq)
);

CREATE TABLE deal_journals (
    id               BIGINT PRIMARY KEY,
    deal_id          UUID         NOT NULL REFERENCES deals (deal_id),
    event            VARCHAR(20)  NOT NULL CHECK (event IN ('SETTLED', 'MATURED', 'REVERSED')),
    -- treasury:<dealId>:<event> — the same key the ledger deduplicates on.
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
    journal_id       UUID         NOT NULL,
    posted_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_deal_journals_deal ON deal_journals (deal_id);

-- Client Idempotency-Key per command (money-path idempotency, #8351): written in the SAME
-- transaction as the state change it produced, so a replayed key finds either nothing (the
-- command never committed, safe to run) or the command (answer with the deal as it stands).
CREATE TABLE deal_commands (
    id               BIGINT PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
    action           VARCHAR(20)  NOT NULL,
    deal_id          UUID         NOT NULL REFERENCES deals (deal_id),
    created_at       TIMESTAMPTZ  NOT NULL
);

-- Transactional outbox (ADR-0003 / ADR-0050), fleet shape; the ADR-0252 synthetic taint column
-- is added by V2 in the fleet's own named migration (check-synthetic-outbox-taint.py).
CREATE TABLE treasury_outbox (
    id              BIGINT PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    claimed_at      TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT treasury_outbox_created_at_plausible CHECK (created_at >= TIMESTAMPTZ '2020-01-01')
);

CREATE INDEX idx_treasury_outbox_status ON treasury_outbox (status, created_at);

CREATE SEQUENCE IF NOT EXISTS counterparties_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS deals_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS deal_transitions_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS deal_journals_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS deal_commands_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS treasury_outbox_seq INCREMENT BY 50;

-- Counterparty master seed (ADR-0315 D4/D9). The three banks are SYNTHETIC — invented sandbox
-- counterparties, not real institutions. ČNB is the real central bank as a counterparty TYPE;
-- the sandbox never transacts with it (ADR-0315 compliance impact). Limits are illustrative.
INSERT INTO counterparties (id, counterparty_id, name, kind, limit_czk, limit_eur, synthetic) VALUES
    (nextval('counterparties_seq'), 'CNB',     'Česká národní banka (deposit facility)', 'CENTRAL_BANK', 100000000000.00, NULL, FALSE),
    (nextval('counterparties_seq'), 'SIMBK-A', 'Sandbox Interbank Alpha (synthetic)',    'BANK', 2000000000.00, 50000000.00, TRUE),
    (nextval('counterparties_seq'), 'SIMBK-B', 'Sandbox Interbank Beta (synthetic)',     'BANK', 1000000000.00, 25000000.00, TRUE),
    (nextval('counterparties_seq'), 'SIMBK-C', 'Sandbox Interbank Gamma (synthetic)',    'BANK',  500000000.00, 10000000.00, TRUE);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
