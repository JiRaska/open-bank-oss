-- Immutable results make every SCA-bound group command replayable after later revisions.
-- Rollback: DROP TABLE IF EXISTS delegation_approval_group_commands;
CREATE TABLE delegation_approval_group_commands (
    sca_session_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES delegation_approval_groups(id),
    owner_party_id UUID NOT NULL,
    group_name VARCHAR(120) NOT NULL,
    members TEXT NOT NULL CHECK (members <> ''),
    approval_threshold INTEGER NOT NULL CHECK (approval_threshold >= 1),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_approval_group_commands_group
    ON delegation_approval_group_commands(group_id, revision);
