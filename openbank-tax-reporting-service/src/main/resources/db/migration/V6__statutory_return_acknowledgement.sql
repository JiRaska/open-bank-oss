-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0367 / #15292: the regulator's answer to a SUBMITTED revision. ČNB acknowledges or rejects
-- each SDAT delivery; status moves SUBMITTED -> ACKNOWLEDGED | REJECTED and these columns record
-- who recorded it, when, and the acknowledgement reference or the rejection reason. Existing rows
-- keep NULLs: no historical submission was ever acknowledged in this table.
--
-- Rollback: ALTER TABLE statutory_return DROP COLUMN acknowledged_by, DROP COLUMN acknowledged_at,
--           DROP COLUMN acknowledgement; (only after no row is ACKNOWLEDGED or REJECTED).
ALTER TABLE statutory_return
    ADD COLUMN acknowledged_by  VARCHAR(255),
    ADD COLUMN acknowledged_at  TIMESTAMPTZ,
    ADD COLUMN acknowledgement  VARCHAR(1024);
