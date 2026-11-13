-- SPDX-License-Identifier: Apache-2.0
-- ADR-0374 D4: a party that loses its standing freezes its accounts, and its return to good
-- standing lifts exactly those freezes. The origin records who froze the account so a freeze placed
-- by an operator or a control is never lifted by a party event.
--
-- Nullable, no backfill: a row already FROZEN has no recorded origin and is treated as an operator
-- freeze, the direction that never unfreezes on its own.
--
-- Rollback: an earlier binary neither reads nor writes the column; an account it unfreezes keeps a
-- stale origin, which chk_accounts_freeze_origin would then refuse. So drop the constraint before
-- rolling the binary back, and the column once nothing reads it:
--   ALTER TABLE accounts DROP CONSTRAINT chk_accounts_freeze_origin;
--   ALTER TABLE accounts DROP COLUMN freeze_origin;

ALTER TABLE accounts ADD COLUMN freeze_origin VARCHAR(20);

ALTER TABLE accounts ADD CONSTRAINT chk_accounts_freeze_origin CHECK (
    freeze_origin IS NULL
    OR (status = 'FROZEN' AND freeze_origin IN ('OPERATOR', 'PARTY_STANDING'))
);
