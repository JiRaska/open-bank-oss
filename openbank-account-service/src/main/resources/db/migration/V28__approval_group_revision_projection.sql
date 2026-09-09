-- ADR-0284 D3. Rollback: DROP TABLE approval_group_revisions;
CREATE TABLE approval_group_revisions (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL,
    owner_party_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    name VARCHAR(120) NOT NULL,
    members TEXT NOT NULL CHECK (members <> ''),
    approval_threshold INTEGER NOT NULL CHECK (approval_threshold >= 1),
    active BOOLEAN NOT NULL,
    CONSTRAINT uq_approval_group_revision UNIQUE (group_id, revision)
);
