-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0352 phase 3 (interest, SHADOW ONLY): InterestService keeps deciding and posting exactly as
-- before and writes nothing new. An asynchronous comparator enqueues committed accrual and
-- capitalisation rows AFTER their transaction, re-decides each with the product IR of the account's
-- pinned revision, and records the outcome here. Additive only; nothing on the posting path reads or
-- writes these tables.
-- Rollback: DROP TABLE interest_product_shadow_input; DROP TRIGGER interest_product_revision_ir_immutable
-- ON interest_product_revision_ir; DROP FUNCTION forbid_interest_revision_ir_mutation();
-- DROP TABLE interest_product_revision_ir. The previous release neither writes nor reads them.

-- One row per legacy decision awaiting (or past) comparison. source_id points at interest_accruals.id
-- or interest_capitalizations.id; no FK, because the row is only ever read back and a source that
-- vanished is recorded as such rather than blocking a delete.
CREATE TABLE interest_product_shadow_input (
    id               UUID PRIMARY KEY,
    source_kind      VARCHAR(16) NOT NULL CHECK (source_kind IN ('ACCRUAL', 'CAPITALIZATION')),
    source_id        UUID        NOT NULL,
    status           VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'CLAIMED', 'DONE')),
    attempts         INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    claimed_at       TIMESTAMPTZ,
    outcome          VARCHAR(64),
    -- The compared IR rule and both amounts, kept so a divergence can be read without re-running it.
    rule_id          VARCHAR(64),
    legacy_amount    NUMERIC(20, 6),
    ir_amount        NUMERIC(20, 6),
    created_at       TIMESTAMPTZ NOT NULL,
    completed_at     TIMESTAMPTZ,
    CONSTRAINT ux_interest_product_shadow_source UNIQUE (source_kind, source_id)
);

CREATE INDEX idx_interest_product_shadow_input_due ON interest_product_shadow_input (status, next_attempt_at);

-- Interest's own immutable copy of a pinned revision's product IR and its publish-path signature
-- (ADR-0352 §6). A revision without IR is recorded too (has_ir = false), because a revision is immutable.
CREATE TABLE interest_product_revision_ir (
    revision_id    UUID PRIMARY KEY,
    has_ir         BOOLEAN     NOT NULL,
    revision_hash  CHAR(64)    CHECK (revision_hash ~ '^[0-9a-f]{64}$'),
    ir_json        TEXT,
    key_id         VARCHAR(128),
    algorithm      VARCHAR(64),
    signature      TEXT,
    -- The full signed envelope identity (ADR-0352 #13749) and the catalog content hash the copy reports;
    -- the shadow trusts a copy only when that content hash equals the one recorded at pin time.
    product_code     VARCHAR(64),
    revision_number  BIGINT,
    ir_version       INTEGER,
    content_hash     CHAR(64),
    fetched_at     TIMESTAMPTZ NOT NULL,
    CHECK (has_ir OR (revision_hash IS NULL AND ir_json IS NULL AND signature IS NULL)),
    CHECK (NOT has_ir OR (revision_hash IS NOT NULL AND ir_json IS NOT NULL))
);

CREATE FUNCTION forbid_interest_revision_ir_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'interest product revision IR copies are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER interest_product_revision_ir_immutable
    BEFORE UPDATE OR DELETE ON interest_product_revision_ir
    FOR EACH ROW EXECUTE FUNCTION forbid_interest_revision_ir_mutation();
