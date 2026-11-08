-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Additive, no historical backfill. Rollback disables delivery; retain source facts and receipts.
-- Business evidence survives the transport outbox's ordinary SENT retention.
CREATE TABLE pension_fund_fee_intents (
    nav_id UUID PRIMARY KEY REFERENCES fund_navs(id),
    fund_id UUID NOT NULL REFERENCES funds(id),
    payload TEXT NOT NULL,
    synthetic BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE pension_fund_accounting_outbox (
    id BIGINT PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL REFERENCES funds(id),
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    synthetic BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ
);
CREATE SEQUENCE pension_fund_accounting_outbox_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX ix_pension_fund_accounting_outbox_claim ON pension_fund_accounting_outbox(created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
CREATE INDEX ix_pension_fund_accounting_outbox_inflight_aggregate
    ON pension_fund_accounting_outbox(aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');

-- Once an intent is assigned to a management company, a config edit must never move it.
CREATE TABLE pension_fund_fee_destinations (
    fund_id UUID PRIMARY KEY REFERENCES funds(id),
    company_book_id UUID NOT NULL,
    bound_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE pension_fund_fee_receipts (
    nav_id UUID NOT NULL REFERENCES fund_navs(id),
    book_kind VARCHAR(32) NOT NULL CHECK (book_kind IN ('PENSION_COMPANY', 'PENSION_FUND')),
    book_id UUID NOT NULL,
    journal_id UUID,
    posting_date DATE NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('POSTED', 'NO_POSTING')),
    synthetic BOOLEAN NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (nav_id, book_kind),
    CHECK ((outcome = 'POSTED') = (journal_id IS NOT NULL))
);
