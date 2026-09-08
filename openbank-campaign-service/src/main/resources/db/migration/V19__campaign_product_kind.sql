-- SPDX-License-Identifier: Apache-2.0
-- ADR-0269 rule 1: a campaign has to say what it is selling, so the credit step gate can refuse.
--
-- Added NULL-able, backfilled, then made NOT NULL, so the statement never rewrites a locked table
-- with a validating constraint in one shot.
--
-- The backfill asserts that every campaign written before this column existed is non-credit. That
-- is true today by construction: nothing could enrol into a credit journey, because the concept
-- did not exist. It is an assertion about history, deliberately made once here rather than as a
-- column DEFAULT -- a standing default would keep silently answering "not credit" for every future
-- insert that forgot to say, which is the failure this column exists to prevent.
-- Rollback: ALTER TABLE campaigns DROP COLUMN product_kind;
ALTER TABLE campaigns ADD COLUMN product_kind varchar(32);

UPDATE campaigns SET product_kind = 'NONE' WHERE product_kind IS NULL;

ALTER TABLE campaigns ALTER COLUMN product_kind SET NOT NULL;
