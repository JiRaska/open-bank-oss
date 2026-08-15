-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- First pending migration after V5: restore defaults before any longer migration can expose a
-- rolling window in which the first v2 binary cannot write its older outbox shape. Replacement
-- child triggers also close INSERT and revision-relocation bypasses without changing V5 checksum.
-- Rollback: retain the defaults during any mixed-version window. After all old binaries are gone,
-- drop the two triggers, restore the prior function, recreate UPDATE/DELETE-only triggers.

ALTER TABLE catalog_outbox ALTER COLUMN headers SET DEFAULT '{}'::jsonb;
ALTER TABLE catalog_outbox ALTER COLUMN created_at SET DEFAULT now();

DROP TRIGGER trg_catalog_price_immutable ON catalog_price_components;
DROP TRIGGER trg_catalog_relationship_immutable ON catalog_relationships;

CREATE OR REPLACE FUNCTION enforce_published_catalog_child_immutability() RETURNS TRIGGER AS $$
DECLARE
    old_owner_state VARCHAR(16);
    new_owner_state VARCHAR(16);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT state INTO old_owner_state FROM catalog_revisions WHERE id = OLD.revision_id;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT state INTO new_owner_state FROM catalog_revisions WHERE id = NEW.revision_id;
    END IF;
    IF old_owner_state IN ('PUBLISHED', 'SUPERSEDED')
        OR new_owner_state IN ('PUBLISHED', 'SUPERSEDED')
    THEN
        RAISE EXCEPTION 'published catalog revision child is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_catalog_price_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON catalog_price_components
    FOR EACH ROW EXECUTE FUNCTION enforce_published_catalog_child_immutability();

CREATE TRIGGER trg_catalog_relationship_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON catalog_relationships
    FOR EACH ROW EXECUTE FUNCTION enforce_published_catalog_child_immutability();
