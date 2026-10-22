-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- #13230: a price component says what it IS (FEE, RATE, LIMIT, THRESHOLD), closed by a CHECK. Nullable
-- on purpose: components authored before roles existed carry none, and their revisions (whose content
-- and hash are immutable once published) are never rewritten. A FEE is always an AMOUNT, as the kernel
-- requires. The revision's content JSON stays the source of truth; this column mirrors it like the
-- other price columns.
--
-- Rollback: ALTER TABLE catalog_price_components DROP COLUMN role;
-- (role values authored meanwhile survive in each revision's content JSON.)

ALTER TABLE catalog_price_components
    ADD COLUMN role VARCHAR(16)
        CHECK (role IN ('FEE', 'RATE', 'LIMIT', 'THRESHOLD')),
    ADD CONSTRAINT ck_catalog_price_fee_is_amount CHECK (role IS DISTINCT FROM 'FEE' OR kind = 'AMOUNT');
