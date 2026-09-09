-- Disclosure lifecycle belongs to delegation-service; document-service owns immutable bytes.
-- Rollback: before production use and only if empty, DROP TABLE delegation_disclosures. After use,
-- retain evidence rows and roll back application producers/consumers only.
CREATE TABLE delegation_disclosures (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    delegation_id UUID NOT NULL REFERENCES delegation_grants(id),
    grantor_party_id UUID NOT NULL,
    source_document_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED', 'READY', 'REJECTED')),
    snapshot_id UUID UNIQUE,
    source_sha256 VARCHAR(64),
    snapshot_sha256 VARCHAR(64),
    size_bytes BIGINT CHECK (size_bytes > 0),
    rejection_reason VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'READY') = (snapshot_id IS NOT NULL)),
    CHECK ((status = 'READY') = (source_sha256 IS NOT NULL)),
    CHECK ((status = 'READY') = (snapshot_sha256 IS NOT NULL)),
    CHECK ((status = 'READY') = (size_bytes IS NOT NULL)),
    CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CHECK (source_sha256 IS NULL OR source_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (snapshot_sha256 IS NULL OR snapshot_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_delegation_disclosures_grant ON delegation_disclosures(delegation_id, created_at);
