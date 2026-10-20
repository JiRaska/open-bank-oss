-- #13429 (ADR-0257): role-qualified revision approval becomes pack data instead of a pension-only
-- kernel feature. The evidence is preserved byte for byte: the table and columns are RENAMED, not
-- copied, so every existing approval keeps its id, digest and timestamp.
-- Rollback: restore from backup taken before this migration, or reverse each RENAME below and
-- re-create the V12/V13 functions; never drop catalog_revision_role_approvals, it is evidence.

-- 1. Approval policies are declared by packs per exact schema version and are append-only.
CREATE TABLE catalog_schema_approval_policies (
    schema_id VARCHAR(255) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    policy JSONB NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (schema_id, schema_version)
);

CREATE TRIGGER trg_catalog_schema_approval_policy_immutable
    BEFORE UPDATE OR DELETE ON catalog_schema_approval_policies
    FOR EACH ROW EXECUTE FUNCTION prevent_catalog_evidence_mutation();

-- The policy V12 enforced in code, registered here so enforcement never depends on the pack seeder
-- having run (a disabled pack must not turn into an unapproved publication path). The seeder
-- asserts its pack.json declares exactly this.
INSERT INTO catalog_schema_approval_policies (schema_id, schema_version, policy, registered_at)
VALUES (
    'org.openbank.retirement.pension-savings',
    2,
    '{"roles":[{"code":"LEGAL_COUNSEL","authority":"PENSION_LEGAL_APPROVER"},{"code":"PRODUCT_OWNER","authority":"PENSION_PRODUCT_OWNER"}],"requireEffectiveFrom":true,"requiredAttributes":{"reviewStatus":"LEGAL_AND_COMMERCIAL_REVIEWED"}}',
    now()
);

-- 2. The evidence table: pack-declared role codes instead of a closed pension role list.
DROP TRIGGER pension_revision_approvals_immutable ON pension_revision_approvals;
ALTER TABLE pension_revision_approvals RENAME TO catalog_revision_role_approvals;
ALTER TABLE catalog_revision_role_approvals DROP CONSTRAINT pension_revision_approvals_role_check;
ALTER TABLE catalog_revision_role_approvals
    ADD CONSTRAINT catalog_revision_role_approvals_role_check CHECK (role ~ '^[A-Z][A-Z0-9_]{1,31}$');

CREATE FUNCTION forbid_revision_role_approval_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'revision role approvals are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER catalog_revision_role_approvals_immutable
    BEFORE UPDATE OR DELETE ON catalog_revision_role_approvals
    FOR EACH ROW EXECUTE FUNCTION forbid_revision_role_approval_mutation();

DROP FUNCTION forbid_pension_revision_approval_mutation();

-- 3. The frozen digest and approved interval on the revision lose their pack name.
DROP TRIGGER pension_publication_digest_immutable ON catalog_revisions;
DROP FUNCTION forbid_published_pension_digest_mutation();
ALTER TABLE catalog_revisions RENAME COLUMN pension_approval_digest TO approval_digest;
ALTER TABLE catalog_revisions RENAME COLUMN pension_approved_effective_to TO approved_effective_to;

CREATE FUNCTION forbid_published_approval_evidence_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state IN ('PUBLISHED', 'SUPERSEDED')
        AND (NEW.approval_digest IS DISTINCT FROM OLD.approval_digest
            OR NEW.approved_effective_to IS DISTINCT FROM OLD.approved_effective_to) THEN
        RAISE EXCEPTION 'published approval evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER catalog_publication_approval_evidence_immutable
    BEFORE UPDATE ON catalog_revisions
    FOR EACH ROW EXECUTE FUNCTION forbid_published_approval_evidence_mutation();
