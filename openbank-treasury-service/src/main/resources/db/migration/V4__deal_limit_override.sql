-- SPDX-License-Identifier: Apache-2.0
-- ADR-0315 D4: a counterparty-limit breach blocks booking unless a second, SENIOR approver records an
-- override with a reason. The override is bounded to the exposure it was granted for
-- (limit_override_exposure): if exposure grows before booking, approval refuses again. All five
-- columns are set together or not at all.
--
-- Rollback: ALTER TABLE deals DROP COLUMN limit_override_by, DROP COLUMN limit_override_reason,
--   DROP COLUMN limit_override_at, DROP COLUMN limit_override_exposure, DROP COLUMN limit_override_limit;

ALTER TABLE deals
    ADD COLUMN limit_override_by        VARCHAR(255),
    ADD COLUMN limit_override_reason    TEXT,
    ADD COLUMN limit_override_at        TIMESTAMPTZ,
    ADD COLUMN limit_override_exposure  NUMERIC(20, 2),
    ADD COLUMN limit_override_limit     NUMERIC(20, 2);

ALTER TABLE deals ADD CONSTRAINT deals_limit_override_complete CHECK (
    (limit_override_by IS NULL AND limit_override_reason IS NULL AND limit_override_at IS NULL
        AND limit_override_exposure IS NULL AND limit_override_limit IS NULL)
    OR (limit_override_by IS NOT NULL AND limit_override_reason IS NOT NULL AND limit_override_at IS NOT NULL
        AND limit_override_exposure IS NOT NULL AND limit_override_limit IS NOT NULL)
);
