-- SPDX-License-Identifier: Apache-2.0
-- Four-eyes (maker-checker) gate for collateral registration (ADR-0028 follow-up, issue #621).
-- A registered collateral item now carries an approval status mirroring loan_application's
-- PROPOSED/APPROVED/REJECTED shape: PENDING until a DIFFERENT principal than registered_by
-- decides it. LendingService.applyCollateral only sums APPROVED collateral into the IFRS 9 LGD
-- adjustment, so a pending or rejected registration cannot reduce a loan's ECL.
--
-- Backfill: every collateral row registered before this migration was recorded under the old,
-- ungated endpoint and was already being consulted by the ECL calc (PR #607) — treating it as
-- PENDING would silently change today's provisioning numbers for the existing book. Backfill to
-- APPROVED with registered_by/decided_by set to a synthetic 'migration:v5-backfill' marker so the
-- audit trail is honest about why these rows have no real checker, without inventing one.
--
-- Rollback: ALTER TABLE collateral DROP COLUMN status, DROP COLUMN registered_by,
--           DROP COLUMN decided_by, DROP COLUMN decided_at; DROP TYPE collateral_status;

CREATE TYPE collateral_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED');

ALTER TABLE collateral
    ADD COLUMN status        collateral_status NOT NULL DEFAULT 'PENDING',
    ADD COLUMN registered_by VARCHAR(128)       NOT NULL DEFAULT 'migration:v5-backfill',
    ADD COLUMN decided_by    VARCHAR(128),
    ADD COLUMN decided_at    TIMESTAMPTZ;

UPDATE collateral
SET status = 'APPROVED', decided_by = 'migration:v5-backfill', decided_at = NOW()
WHERE status = 'PENDING';

ALTER TABLE collateral ALTER COLUMN registered_by DROP DEFAULT;

CREATE INDEX idx_collateral_status ON collateral(status);
