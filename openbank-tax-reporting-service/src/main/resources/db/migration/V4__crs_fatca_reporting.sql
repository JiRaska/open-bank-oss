-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0340 B: CRS/FATCA information-exchange assembly on the ADR-0336 statutory-returns lifecycle.
--
-- statutory_return_line — the per-account reportable lines of a return whose catalogue definition
-- carries a lineSchema (today: CRS/FATCA). Lines ride the return's revision machinery: a correction
-- is a new return revision, and the old revision's lines stay exactly what was attested and
-- submitted. fields_json is the canonical serialised line (keyed by catalogue lineSchema field id),
-- covered by the return content_hash alongside the datapoints.
--
-- observed_income_credit — every `interest.withholding.recorded.v1` the fleet announces on
-- openbank.interest.accrual.event, keyed by withholding_id so a Kafka redelivery is a no-op. This is
-- the GROSS interest credited per account and accrual period — the CRS/FATCA reportable income; the
-- withholding treatment is never re-derived here (ADR-0340 E: reporting reads income events, never
-- the tax decision).
--
-- Rollback:
--   DROP TABLE statutory_return_line;
--   DROP TABLE observed_income_credit;
-- (tax-reporting owns both; no other table references them).
CREATE TABLE statutory_return_line (
    return_id  UUID         NOT NULL REFERENCES statutory_return (id),
    line_index INTEGER      NOT NULL,
    fields_json TEXT        NOT NULL,
    PRIMARY KEY (return_id, line_index)
);

-- The account lookup index casts to jsonb for the expression; the column itself stays TEXT so a
-- JSON number round-trip can never alter the scale of a figure whose hash was attested (same
-- reasoning as datapoints_json).
CREATE INDEX ix_statutory_return_line_account ON statutory_return_line ((fields_json::jsonb ->> 'account_ref'));

CREATE TABLE observed_income_credit (
    withholding_id   UUID          PRIMARY KEY,
    capitalization_id UUID         NOT NULL,
    account_id       UUID          NOT NULL,
    product_id       UUID          NOT NULL,
    period_from      DATE          NOT NULL,
    period_to        DATE          NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    gross_amount     NUMERIC(19, 4) NOT NULL,
    net_amount       NUMERIC(19, 4) NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL,
    observed_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_observed_income_credit_window ON observed_income_credit (period_to, account_id);
