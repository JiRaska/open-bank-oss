-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Preserve the ISO quantity dimension without inventing it for existing evidence.
ALTER TABLE portfolio_holdings ADD COLUMN quantity_basis VARCHAR(20);
ALTER TABLE portfolio_holdings ADD CONSTRAINT ck_portfolio_quantity_basis
    CHECK (quantity_basis IS NULL OR quantity_basis IN ('UNIT', 'FACE_AMOUNT', 'AMORTISED_VALUE'));
-- Legacy NULL holdings remain immutable evidence and cannot be aggregated for reporting.
-- Recovery: upload an explicit source correction for the same account/date; byte-identical
-- replays retain their original version and therefore cannot repair missing metadata.
-- Rollback: stop writes, deploy the prior compatible binary, then DROP CONSTRAINT
-- ck_portfolio_quantity_basis and DROP COLUMN quantity_basis only after preserving the
-- new dimension evidence. Prior binaries cannot safely report mixed quantity dimensions;
-- leave period-end reporting disabled until forward recovery. Never backfill from CFI.
