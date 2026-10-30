-- ADR-0352 §7/§11.4 (phase 5b): nobody approves a behaviour change without having seen what it does.
-- One row per VALID behavioural-diff backtest a principal ran against a revision, bound to the
-- revision's approval digest (content + row version), so an edit after the backtest invalidates it.
-- publish-ir and role approvals of an IR-bearing revision require a row for the approving principal
-- and the current digest. Evidence: written once, never updated or deleted.
-- Rollback: DROP TRIGGER catalog_revision_backtests_immutable ON catalog_revision_backtests;
--           DROP FUNCTION forbid_revision_backtest_mutation(); DROP TABLE catalog_revision_backtests;
--           Rolling back re-opens approval of IR revisions without a backtest; also revert the
--           application gate in the same release.

CREATE TABLE catalog_revision_backtests (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL REFERENCES catalog_revisions (id),
    digest CHAR(64) NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    principal_name VARCHAR(256) NOT NULL CHECK (principal_name <> ''),
    issuer VARCHAR(512),
    subject VARCHAR(256),
    base_revision_hash CHAR(64) NOT NULL CHECK (base_revision_hash ~ '^[0-9a-f]{64}$'),
    candidate_revision_hash CHAR(64) NOT NULL CHECK (candidate_revision_hash ~ '^[0-9a-f]{64}$'),
    diff_sha256 CHAR(64) NOT NULL CHECK (diff_sha256 ~ '^[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_catalog_revision_backtests_revision ON catalog_revision_backtests (revision_id, digest);

CREATE FUNCTION forbid_revision_backtest_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'revision backtests are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER catalog_revision_backtests_immutable
    BEFORE UPDATE OR DELETE ON catalog_revision_backtests
    FOR EACH ROW EXECUTE FUNCTION forbid_revision_backtest_mutation();
