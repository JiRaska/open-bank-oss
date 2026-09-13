-- ADR-0284 D3: owner-managed, versioned approval rosters for future operation snapshots.
-- Rollback: DROP TABLE delegation_approval_group_members; DROP TABLE delegation_approval_groups;

CREATE TABLE delegation_approval_groups (
    id UUID PRIMARY KEY,
    owner_party_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    approval_threshold INTEGER NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_sca_session_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_approval_group_threshold_positive CHECK (approval_threshold >= 1),
    CONSTRAINT chk_approval_group_revision_positive CHECK (revision >= 1)
);

CREATE TABLE delegation_approval_group_members (
    group_id UUID NOT NULL REFERENCES delegation_approval_groups(id) ON DELETE CASCADE,
    member_party_id UUID NOT NULL,
    PRIMARY KEY (group_id, member_party_id)
);

CREATE INDEX idx_approval_group_owner ON delegation_approval_groups(owner_party_id, active);
