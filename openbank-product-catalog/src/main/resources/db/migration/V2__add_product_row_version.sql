-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- P0 / ADR-0257: optimistic concurrency for mutable legacy products. The default makes this an
-- expand-only migration: old binaries ignore the column and new binaries can read every V1 row.
-- The trigger closes the mixed-version rollout gap: an old writer does not know row_version, so the
-- database advances it; a new Hibernate @Version writer already advances it, so the trigger leaves
-- that value untouched. This remains useful for approved SQL maintenance writers after rollout.
-- Rollback (only before a writer relies on revision): DROP TRIGGER products_row_version_trigger ON
-- products; DROP FUNCTION bump_products_row_version(); ALTER TABLE products DROP COLUMN row_version.

ALTER TABLE products ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

CREATE FUNCTION bump_products_row_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.row_version <= OLD.row_version THEN
        NEW.row_version := OLD.row_version + 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER products_row_version_trigger
BEFORE UPDATE ON products
FOR EACH ROW
EXECUTE FUNCTION bump_products_row_version();
