-- SPDX-License-Identifier: AGPL-3.0-only
-- Handles are SHA-256 digests, never OAuth bearer or refresh token values.
-- Rollback: disable hosted token issuance and resolution before dropping tokens and families.
CREATE TABLE mcp_hosted_vendor_families (
    family_id uuid PRIMARY KEY,
    connection_id uuid NOT NULL,
    vendor text NOT NULL CHECK (vendor IN ('CHATGPT', 'CLAUDE')),
    generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE INDEX mcp_hosted_vendor_families_connection_idx
    ON mcp_hosted_vendor_families (connection_id, vendor);
CREATE INDEX mcp_hosted_vendor_families_expiry_idx
    ON mcp_hosted_vendor_families (expires_at);

CREATE TABLE mcp_hosted_vendor_tokens (
    token_digest text PRIMARY KEY CHECK (token_digest ~ '^[A-Za-z0-9_-]{43}$'),
    kind text NOT NULL CHECK (kind IN ('ACCESS', 'REFRESH')),
    family_id uuid NOT NULL REFERENCES mcp_hosted_vendor_families ON DELETE CASCADE,
    generation bigint NOT NULL CHECK (generation >= 0),
    expires_at timestamptz NOT NULL
);

CREATE INDEX mcp_hosted_vendor_tokens_family_idx ON mcp_hosted_vendor_tokens (family_id);
CREATE INDEX mcp_hosted_vendor_tokens_expiry_idx ON mcp_hosted_vendor_tokens (expires_at);
