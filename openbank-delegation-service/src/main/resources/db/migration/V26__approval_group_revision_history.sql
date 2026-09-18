-- ADR-0284 D3. Append-only history survives event retention and supports projection rebuilds.
-- Rollback: DROP TABLE delegation_approval_group_revisions;

CREATE TABLE delegation_approval_group_revisions (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES delegation_approval_groups(id),
    owner_party_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    name VARCHAR(120) NOT NULL,
    members TEXT NOT NULL CHECK (members <> ''),
    approval_threshold INTEGER NOT NULL CHECK (approval_threshold >= 1),
    active BOOLEAN NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_delegation_approval_group_revision UNIQUE (group_id, revision)
);

CREATE INDEX idx_approval_group_revision_owner
    ON delegation_approval_group_revisions(owner_party_id, group_id, revision);
