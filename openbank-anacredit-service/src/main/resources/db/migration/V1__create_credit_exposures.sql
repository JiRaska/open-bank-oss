-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0037 v2: persist AnaCredit credit exposures (was an in-memory ConcurrentHashMap, ADR-0037's
-- own "Consequences — Neutral" note flagged persistence as a mechanical follow-up). One row per
-- credit instrument, keyed by the durable instrument_id the feed upserts on. debtor_id is indexed
-- because the €25 000 reporting threshold is evaluated per-debtor across all their instruments
-- (AnaCreditReturnBuilder groups by debtor on every render).
--
-- Rollback: DROP TABLE credit_exposures;

CREATE TABLE credit_exposures (
    instrument_id          VARCHAR(64)     PRIMARY KEY,
    debtor_id              VARCHAR(64)     NOT NULL,
    debtor_type            VARCHAR(16)     NOT NULL,
    instrument_type        VARCHAR(24)     NOT NULL,
    currency               VARCHAR(3)      NOT NULL,
    committed_amount       NUMERIC(20, 2)  NOT NULL,
    drawn_amount           NUMERIC(20, 2)  NOT NULL,
    committed_amount_eur   NUMERIC(20, 2)  NOT NULL,
    arrears_amount         NUMERIC(20, 2)  NOT NULL DEFAULT 0,
    defaulted              BOOLEAN         NOT NULL DEFAULT FALSE,
    origination_date       DATE            NOT NULL,
    updated_at             TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_credit_exposures_debtor_id ON credit_exposures (debtor_id);
