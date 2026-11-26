-- SPDX-License-Identifier: AGPL-3.0-only
-- Only digests and authenticated, encrypted transaction data are persisted.
-- Rollback before activation: DROP TABLE mcp_hosted_oauth_transactions.
-- After activation, disable the facade and drain pending flows before removing this table.
CREATE TABLE mcp_hosted_oauth_transactions (
    kind text NOT NULL CHECK (kind IN ('BANK_CALLBACK', 'VENDOR_CODE')),
    handle_digest text NOT NULL CHECK (handle_digest ~ '^[A-Za-z0-9_-]{43}$'),
    vendor text NOT NULL CHECK (vendor IN ('CHATGPT', 'CLAUDE')),
    binding_digest text NOT NULL CHECK (binding_digest ~ '^[A-Za-z0-9_-]{43}$'),
    sealed_payload bytea NOT NULL CHECK (octet_length(sealed_payload) BETWEEN 1 AND 8192),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (kind, handle_digest)
);

CREATE INDEX mcp_hosted_oauth_transactions_expiry_idx
    ON mcp_hosted_oauth_transactions (expires_at);
