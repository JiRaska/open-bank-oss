-- SPDX-License-Identifier: Apache-2.0
-- ADR-0314 D4 / ADR-0315 D6: the bank's own money-market deals enter the snapshot as contract-level
-- instruments, maintained from treasury-service's `treasury.deal.*` events (topic
-- openbank.treasury.deal.events). treasury_deal is the engine's own read model: one row per deal,
-- whose state only ever advances (BOOKED < SETTLED < MATURED | REVERSED), so a redelivered or
-- out-of-order event changes nothing. rate arrives on `booked` only. value_date and maturity_date
-- are nullable because a deal first seen through `reversed` carries neither.
--
-- snapshot_position gains the TREASURY_DEAL kind, shaped like LOAN (an instrument, no sub-account).
--
-- Rollback: DROP TABLE treasury_deal; restore the V3 ck_snapshot_position_kind(_shape) constraints
-- after deleting TREASURY_DEAL positions.

CREATE TABLE treasury_deal (
    deal_id          UUID          PRIMARY KEY,
    product          VARCHAR(32)   NOT NULL,
    counterparty_id  VARCHAR(64)   NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    principal        NUMERIC(20,2) NOT NULL,
    rate             NUMERIC(9,6),
    value_date       DATE,
    maturity_date    DATE,
    state            VARCHAR(16)   NOT NULL CHECK (state IN ('BOOKED', 'SETTLED', 'MATURED', 'REVERSED')),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

ALTER TABLE snapshot_position DROP CONSTRAINT ck_snapshot_position_kind;
ALTER TABLE snapshot_position DROP CONSTRAINT ck_snapshot_position_kind_shape;
ALTER TABLE snapshot_position ADD CONSTRAINT ck_snapshot_position_kind
    CHECK (position_kind IN ('SUB_LEDGER', 'GL_ACCOUNT', 'LOAN', 'TREASURY_DEAL'));
ALTER TABLE snapshot_position ADD CONSTRAINT ck_snapshot_position_kind_shape CHECK (
    (position_kind = 'SUB_LEDGER' AND sub_account_id IS NOT NULL AND instrument_id IS NULL)
    OR (position_kind = 'GL_ACCOUNT' AND sub_account_id IS NULL AND gl_account_code IS NOT NULL
        AND instrument_id IS NULL)
    OR (position_kind IN ('LOAN', 'TREASURY_DEAL') AND sub_account_id IS NULL AND instrument_id IS NOT NULL)
);
