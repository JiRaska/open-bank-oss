-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

CREATE TABLE mcp_connect_requests (
    request_ref CHAR(64) PRIMARY KEY CHECK (request_ref ~ '^[0-9a-f]{64}$'),
    party_id UUID NOT NULL,
    client_id VARCHAR(255) NOT NULL,
    par_jkt CHAR(43) NOT NULL CHECK (par_jkt ~ '^[A-Za-z0-9_-]{43}$'),
    redirect_host VARCHAR(253) NOT NULL,
    resource VARCHAR(2048) NOT NULL,
    scopes TEXT[] NOT NULL CHECK (cardinality(scopes) > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    challenge_id UUID UNIQUE,
    context_sha256 CHAR(64) CHECK (context_sha256 ~ '^[0-9a-f]{64}$'),
    code_binding_ref CHAR(64) UNIQUE CHECK (code_binding_ref ~ '^[0-9a-f]{64}$'),
    staged_at TIMESTAMPTZ,
    refresh_family_ref CHAR(64) UNIQUE CHECK (refresh_family_ref ~ '^[0-9a-f]{64}$'),
    connection_id UUID UNIQUE,
    activated_at TIMESTAMPTZ,
    grant_valid_until TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK ((challenge_id IS NULL) = (context_sha256 IS NULL)),
    CHECK ((code_binding_ref IS NULL) = (staged_at IS NULL)),
    CHECK (staged_at IS NULL OR (challenge_id IS NOT NULL AND staged_at < expires_at)),
    CHECK ((refresh_family_ref IS NULL) = (connection_id IS NULL)),
    CHECK ((connection_id IS NULL) = (activated_at IS NULL)),
    CHECK ((grant_valid_until IS NULL) = (activated_at IS NULL)),
    CHECK (activated_at IS NULL OR (staged_at IS NOT NULL AND activated_at >= staged_at AND activated_at < expires_at)),
    CHECK (grant_valid_until IS NULL OR grant_valid_until > activated_at),
    CHECK (revoked_at IS NULL OR (activated_at IS NOT NULL AND revoked_at >= activated_at))
);

CREATE INDEX idx_mcp_connect_requests_party_expiry ON mcp_connect_requests (party_id, expires_at);

ALTER TABLE delegation_grants
    ADD COLUMN mcp_connection_id UUID REFERENCES mcp_connect_requests (connection_id),
    ADD CONSTRAINT chk_delegation_mcp_connection_grantee CHECK (
        mcp_connection_id IS NULL OR grantee_type = 'MCP_CLIENT'
    );

CREATE INDEX idx_delegation_mcp_connection ON delegation_grants (mcp_connection_id)
    WHERE mcp_connection_id IS NOT NULL;

CREATE TABLE mcp_connect_selected_accounts (
    request_ref CHAR(64) NOT NULL REFERENCES mcp_connect_requests (request_ref) ON DELETE CASCADE,
    account_id UUID NOT NULL,
    capabilities TEXT[] NOT NULL CHECK (cardinality(capabilities) > 0),
    grant_id UUID UNIQUE REFERENCES delegation_grants (id),
    PRIMARY KEY (request_ref, account_id)
);

-- Rollback: disable Connect V2 first. Leave both tables in place during application rollback
-- so a later rollout can recover activation receipts. Drop the selection table and the grant
-- connection column before the parent only when retention permits loss of the activation records.
