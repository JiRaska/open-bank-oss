-- SPDX-License-Identifier: Apache-2.0
-- Bank provenance is nullable for old documents: historical JSON metadata could
-- have been supplied by a caller, so backfilling it would manufacture trust.
-- Rollback before any writer: ALTER TABLE documents DROP COLUMN bank_scope;
-- After adoption, stop the new proof reader/writer and retain the column;
-- dropping populated provenance would invalidate reviewed evidence.
ALTER TABLE documents ADD COLUMN bank_scope VARCHAR(64)
    CHECK (bank_scope IS NULL OR bank_scope ~ '^[a-z0-9][a-z0-9-]{0,63}$');

CREATE FUNCTION guard_document_bank_scope_immutable() RETURNS trigger AS $$
BEGIN
    IF NEW.bank_scope IS DISTINCT FROM OLD.bank_scope THEN
        RAISE EXCEPTION 'document bank scope is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER documents_bank_scope_immutable
    BEFORE UPDATE OF bank_scope ON documents
    FOR EACH ROW EXECUTE FUNCTION guard_document_bank_scope_immutable();
