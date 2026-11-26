-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

CREATE TABLE mcp_oauth_handoffs (
    token_sha256 CHAR(64) PRIMARY KEY CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    request_ref CHAR(64) NOT NULL UNIQUE CHECK (request_ref ~ '^[0-9a-f]{64}$'),
    issuer VARCHAR(2048) NOT NULL,
    client_id VARCHAR(255) NOT NULL,
    resource VARCHAR(2048) NOT NULL,
    redirect_uri VARCHAR(2048) NOT NULL,
    session_ref CHAR(64) NOT NULL CHECK (session_ref ~ '^[0-9a-f]{64}$'),
    binding_kind VARCHAR(6) NOT NULL CHECK (binding_kind IN ('HOSTED', 'DPOP')),
    par_jkt CHAR(43) CHECK (par_jkt ~ '^[A-Za-z0-9_-]{43}$'),
    scopes TEXT[] NOT NULL CHECK (cardinality(scopes) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    party_id UUID,
    keycloak_subject VARCHAR(255),
    claimed_at TIMESTAMPTZ,
    context_sha256 CHAR(64) CHECK (context_sha256 ~ '^[0-9a-f]{64}$'),
    proved_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    CHECK ((par_jkt IS NULL) = (binding_kind = 'HOSTED')),
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '5 minutes'),
    CHECK ((party_id IS NULL) = (claimed_at IS NULL)),
    CHECK ((keycloak_subject IS NULL) = (claimed_at IS NULL)),
    CHECK (claimed_at IS NULL OR (claimed_at >= created_at AND claimed_at < expires_at)),
    CHECK ((context_sha256 IS NULL) = (proved_at IS NULL)),
    CHECK (proved_at IS NULL OR (claimed_at IS NOT NULL AND proved_at >= claimed_at AND proved_at < expires_at)),
    CHECK (consumed_at IS NULL OR (proved_at IS NOT NULL AND consumed_at >= proved_at AND consumed_at < expires_at))
);

-- Rollback: disable native handoff issuance and consumption before rolling back application code.
-- The additive table can remain in place. Drop it in a forward migration once all handoffs have
-- expired and the dormant state has been confirmed. No existing Connect grants depend on it.
