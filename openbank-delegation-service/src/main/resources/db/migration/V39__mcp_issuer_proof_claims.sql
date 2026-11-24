-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0383 D4: durable issuer-only single-use claim. The key is a realm-scoped HMAC-SHA-256
-- reference. No OAuth credentials, proof claims, party ids or account ids are stored.
-- Five minutes covers the proposed issuer proof window (10s past, 5s future) and clock margin.
-- Any change to the accepted proof horizon must review this retention before it rolls out.
--
-- Rollback: stop all issuer claim callers and keep issuance closed for the full five-minute
-- retention interval since the latest possible claim. Then a forward migration can
-- DROP TABLE mcp_issuer_proof_claims. Never truncate or drop while issuance is active.

CREATE TABLE mcp_issuer_proof_claims (
    proof_key    VARCHAR(64) PRIMARY KEY,
    retain_until TIMESTAMPTZ NOT NULL DEFAULT (statement_timestamp() + INTERVAL '5 minutes'),
    CONSTRAINT chk_mcp_issuer_proof_key CHECK (proof_key ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_mcp_issuer_proof_claims_expiry
    ON mcp_issuer_proof_claims (retain_until, proof_key);
