-- #12384: immutable, role-qualified evidence for each exact pension catalog revision.
-- Rollback: stop pension publication, preserve this evidentiary table in backup, then drop its
-- trigger/function and table only after the approved migration rollback plan is executed.
CREATE TABLE pension_revision_approvals (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES catalog_revisions (id),
    role VARCHAR(32) NOT NULL CHECK (role IN ('LEGAL_COUNSEL', 'PRODUCT_OWNER')),
    issuer VARCHAR(512) NOT NULL CHECK (issuer <> ''),
    subject VARCHAR(512) NOT NULL CHECK (subject <> ''),
    digest CHAR(64) NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    approved_at TIMESTAMPTZ NOT NULL,
    UNIQUE (revision_id, role, digest)
);

CREATE FUNCTION forbid_pension_revision_approval_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'pension revision approvals are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pension_revision_approvals_immutable
    BEFORE UPDATE OR DELETE ON pension_revision_approvals
    FOR EACH ROW EXECUTE FUNCTION forbid_pension_revision_approval_mutation();
