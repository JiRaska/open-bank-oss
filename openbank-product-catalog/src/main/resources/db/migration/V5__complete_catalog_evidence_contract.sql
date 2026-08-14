-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0257 evidence and effective-price completion. Additive and ignored by P0/P1 binaries.
-- Rollback: stop v2 writers, then drop the child immutability triggers/functions and the added
-- effective_from/effective_to, headers and created_at columns. Never roll back after publication.

ALTER TABLE catalog_price_components
    ADD COLUMN effective_from TIMESTAMPTZ,
    ADD COLUMN effective_to TIMESTAMPTZ,
    ADD CONSTRAINT ck_catalog_price_effective_range
        CHECK (effective_from IS NULL OR effective_to IS NULL OR effective_to > effective_from);

ALTER TABLE catalog_outbox
    ADD COLUMN headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN created_at TIMESTAMPTZ;

UPDATE catalog_outbox SET created_at = occurred_at WHERE created_at IS NULL;

ALTER TABLE catalog_outbox ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE catalog_outbox ALTER COLUMN headers DROP DEFAULT;

CREATE OR REPLACE FUNCTION enforce_catalog_outbox_payload_immutability() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.aggregate_type <> OLD.aggregate_type
        OR NEW.aggregate_id <> OLD.aggregate_id
        OR NEW.event_type <> OLD.event_type
        OR NEW.schema_version <> OLD.schema_version
        OR NEW.occurred_at <> OLD.occurred_at
        OR NEW.headers <> OLD.headers
        OR NEW.created_at <> OLD.created_at
        OR NEW.payload <> OLD.payload
    THEN
        RAISE EXCEPTION 'catalog outbox event % payload is immutable', OLD.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION enforce_published_catalog_child_immutability() RETURNS TRIGGER AS $$
DECLARE
    owner_state VARCHAR(16);
BEGIN
    SELECT state INTO owner_state
      FROM catalog_revisions
     WHERE id = COALESCE(OLD.revision_id, NEW.revision_id);
    IF owner_state IN ('PUBLISHED', 'SUPERSEDED') THEN
        RAISE EXCEPTION 'published catalog revision child is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_catalog_price_immutable
    BEFORE UPDATE OR DELETE ON catalog_price_components
    FOR EACH ROW EXECUTE FUNCTION enforce_published_catalog_child_immutability();

CREATE TRIGGER trg_catalog_relationship_immutable
    BEFORE UPDATE OR DELETE ON catalog_relationships
    FOR EACH ROW EXECUTE FUNCTION enforce_published_catalog_child_immutability();
