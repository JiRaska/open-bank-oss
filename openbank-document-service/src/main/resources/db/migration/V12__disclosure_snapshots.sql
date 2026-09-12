-- Immutable, content-addressed emissions for external disclosure (ADR-0232 D7).
-- No UPDATE/DELETE application path exists; revocation belongs to delegation-service and prevents
-- redemption without rewriting evidentiary bytes already emitted.
-- Rollback: before production use and only after proving this table is empty, DROP TRIGGER
-- disclosure_snapshots_immutable ON disclosure_snapshots; DROP FUNCTION
-- reject_disclosure_snapshot_mutation(); DROP TABLE disclosure_snapshots. Once snapshots exist,
-- retain the table and blobs as evidence and roll back only the application consumers/producers.
CREATE TABLE disclosure_snapshots (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    source_document_id UUID NOT NULL REFERENCES documents(id),
    party_ref VARCHAR(200) NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key VARCHAR(300) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL CHECK (content_type = 'application/pdf'),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_disclosure_snapshots_source ON disclosure_snapshots(source_document_id, created_at);

CREATE FUNCTION reject_disclosure_snapshot_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'disclosure snapshots are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER disclosure_snapshots_immutable
    BEFORE UPDATE OR DELETE ON disclosure_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_disclosure_snapshot_mutation();
