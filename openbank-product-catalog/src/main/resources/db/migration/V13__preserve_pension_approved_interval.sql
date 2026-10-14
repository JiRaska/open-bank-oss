-- #12384: preserve the authored interval when a successor shortens the effective window.
-- Rollback: pause pension publication; retain the value in backup before dropping the column.
ALTER TABLE catalog_revisions
    ADD COLUMN pension_approved_effective_to TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION forbid_published_pension_digest_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state IN ('PUBLISHED', 'SUPERSEDED')
        AND (NEW.pension_approval_digest IS DISTINCT FROM OLD.pension_approval_digest
            OR NEW.pension_approved_effective_to IS DISTINCT FROM OLD.pension_approved_effective_to) THEN
        RAISE EXCEPTION 'published pension approval evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
