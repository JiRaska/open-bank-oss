-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0352 phase 3 (billing, SHADOW ONLY): billing keeps deciding fees exactly as before. Each
-- non-skipped assessment additionally queues its frozen inputs and legacy decision, in the same
-- transaction, for an asynchronous comparator that re-decides them with the product IR of the
-- account's pinned revision. Additive only; nothing on the posting path reads these tables.
-- Rollback: DROP TABLE billing_product_shadow_input; DROP TRIGGER billing_product_revision_ir_immutable
-- ON billing_product_revision_ir; DROP FUNCTION forbid_billing_revision_ir_mutation();
-- DROP TABLE billing_product_revision_ir. The previous release neither writes nor reads them.

-- The outbox of legacy decisions awaiting comparison. One row per assessment.
CREATE TABLE billing_product_shadow_input (
    id                        UUID PRIMARY KEY,
    assessment_id             UUID        NOT NULL UNIQUE REFERENCES billing_cycle_assessment (id),
    cycle_id                  VARCHAR(64) NOT NULL,
    account_id                VARCHAR(64) NOT NULL,
    currency                  VARCHAR(3)  NOT NULL,
    product_revision_id       UUID        NOT NULL,
    balance                   NUMERIC(20, 4),
    monthly_turnover          NUMERIC(20, 4),
    aggregate_pocket_balance  NUMERIC(20, 4),
    segment                   VARCHAR(64),
    -- The legacy decision, one {feeId, chargedAmount, waived} object per assessed fee.
    legacy_fees               TEXT        NOT NULL,
    status                    VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'DONE')),
    attempts                  INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at           TIMESTAMPTZ NOT NULL,
    claimed_at                TIMESTAMPTZ,
    outcome                   VARCHAR(64),
    created_at                TIMESTAMPTZ NOT NULL,
    completed_at              TIMESTAMPTZ
);

CREATE INDEX idx_billing_product_shadow_input_due ON billing_product_shadow_input (status, next_attempt_at);

-- Billing's own immutable copy of a pinned revision's product IR and its publish-path signature
-- (ADR-0352 §6: the consumer reads a local signed copy, never the catalog, when it decides). A
-- revision without IR is recorded too (has_ir = false), because a revision is immutable.
CREATE TABLE billing_product_revision_ir (
    revision_id    UUID PRIMARY KEY,
    has_ir         BOOLEAN     NOT NULL,
    revision_hash  CHAR(64)    CHECK (revision_hash ~ '^[0-9a-f]{64}$'),
    ir_json        TEXT,
    key_id         VARCHAR(128),
    algorithm      VARCHAR(64),
    signature      TEXT,
    fetched_at     TIMESTAMPTZ NOT NULL,
    CHECK (has_ir OR (revision_hash IS NULL AND ir_json IS NULL AND signature IS NULL)),
    CHECK (NOT has_ir OR (revision_hash IS NOT NULL AND ir_json IS NOT NULL))
);

CREATE FUNCTION forbid_billing_revision_ir_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'billing product revision IR copies are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER billing_product_revision_ir_immutable
    BEFORE UPDATE OR DELETE ON billing_product_revision_ir
    FOR EACH ROW EXECUTE FUNCTION forbid_billing_revision_ir_mutation();
