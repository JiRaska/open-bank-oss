-- ADR-0352 §4 (Product as Code, phase 2): a revision whose content carries product IR is signed at
-- publication. The detached signature over the IR's canonical revisionHash is evidence — one row per
-- published IR-bearing revision, written once by publish-ir, never updated or deleted.
-- Rollback: DROP TRIGGER catalog_revision_ir_signatures_immutable ON catalog_revision_ir_signatures;
--           DROP FUNCTION forbid_revision_ir_signature_mutation(); DROP TABLE catalog_revision_ir_signatures;
--           Safe only while no consumer has pinned a signed revision (ADR-0352 phase 3); after that a
--           dropped signature makes every pinned contract fail closed at its next posting.

CREATE TABLE catalog_revision_ir_signatures (
    revision_id UUID PRIMARY KEY REFERENCES catalog_revisions (id),
    revision_hash CHAR(64) NOT NULL CHECK (revision_hash ~ '^[0-9a-f]{64}$'),
    key_id VARCHAR(128) NOT NULL CHECK (key_id <> ''),
    algorithm VARCHAR(64) NOT NULL CHECK (algorithm <> ''),
    signature TEXT NOT NULL CHECK (signature <> ''),
    signed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_catalog_revision_ir_signatures_hash ON catalog_revision_ir_signatures (revision_hash);

CREATE FUNCTION forbid_revision_ir_signature_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'revision IR signatures are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER catalog_revision_ir_signatures_immutable
    BEFORE UPDATE OR DELETE ON catalog_revision_ir_signatures
    FOR EACH ROW EXECUTE FUNCTION forbid_revision_ir_signature_mutation();
