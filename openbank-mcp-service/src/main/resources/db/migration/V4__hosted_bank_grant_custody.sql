-- SPDX-License-Identifier: AGPL-3.0-only
-- Rollback: disable hosted sign-in and routing and revoke bank families before dropping the table.
-- Backups restore only cryptographically protected credentials and do not restore revoked access.
CREATE TABLE mcp_hosted_bank_grants (
    connection_id uuid PRIMARY KEY,
    vendor varchar(16) NOT NULL CHECK (vendor IN ('CHATGPT', 'CLAUDE')),
    sealed_tokens text NOT NULL CHECK (
        char_length(sealed_tokens) <= 65560 AND sealed_tokens LIKE 'vault:v%'
    ),
    expires_at timestamptz NOT NULL,
    rotation_id uuid
);

CREATE INDEX mcp_hosted_bank_grants_expiry_idx
    ON mcp_hosted_bank_grants (expires_at);
