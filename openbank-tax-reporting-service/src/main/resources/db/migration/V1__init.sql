-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0180 — §38d Vyuctovani dane vybirane srazkou: the statutory filing's system of record.
--
-- ADR-0038 closed the withholding CASH leg (interest-service assembles and remits) and delegated
-- the statutory FILING to "the downstream payment/reporting consumer" without naming one, so the
-- return was never produced. These two tables are that consumer's state.

-- Every remittance batch observed on interest.withholding.remitted.v1, verbatim.
-- Primary key IS the producer's remittance id: Kafka is at-least-once and this service is a second
-- consumer group, so a redelivery after a rebalance is routine. Counting a batch twice would
-- overstate the tax on a statutory return, so the key makes that impossible rather than unlikely.
CREATE TABLE tax_observed_remittance (
    remittance_id    UUID PRIMARY KEY,
    period_year      INT           NOT NULL,
    period_month     INT           NOT NULL CHECK (period_month BETWEEN 1 AND 12),
    currency         VARCHAR(3)    NOT NULL,
    total_tax_amount NUMERIC(20,6) NOT NULL CHECK (total_tax_amount >= 0),
    item_count       INT           NOT NULL CHECK (item_count >= 0),
    due_date         DATE          NOT NULL,
    observed_at      TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_observed_remittance_period ON tax_observed_remittance (period_year, period_month);

-- One §38d return per calendar month. OPEN -> ASSEMBLED -> FILED.
CREATE TABLE tax_filing (
    id               UUID PRIMARY KEY,
    period_year      INT           NOT NULL,
    period_month     INT           NOT NULL CHECK (period_month BETWEEN 1 AND 12),
    status           VARCHAR(16)   NOT NULL DEFAULT 'OPEN'
        CONSTRAINT chk_tax_filing_status CHECK (status IN ('OPEN', 'ASSEMBLED', 'FILED')),
    currency         VARCHAR(3)    NOT NULL,
    total_tax_amount NUMERIC(20,6) NOT NULL DEFAULT 0,
    remittance_count INT           NOT NULL DEFAULT 0,
    item_count       INT           NOT NULL DEFAULT 0,
    assembled_at     TIMESTAMPTZ,
    assembled_by     VARCHAR(255),
    filed_at         TIMESTAMPTZ,
    filed_by         VARCHAR(255),
    filing_reference VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_tax_filing_period UNIQUE (period_year, period_month),
    -- A FILED return without a reference cannot be evidenced later, which defeats recording it.
    CONSTRAINT chk_tax_filing_reference CHECK (status <> 'FILED' OR filing_reference IS NOT NULL)
);

CREATE INDEX idx_tax_filing_open ON tax_filing (period_year, period_month) WHERE status <> 'FILED';

-- Rollback:
--   DROP TABLE IF EXISTS tax_filing;
--   DROP TABLE IF EXISTS tax_observed_remittance;
-- Safe before any period is FILED: the observed rows are replayable from the topic (subject to
-- retention). After a FILED row exists it is the record of a submitted tax return -- archive first.
