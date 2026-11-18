-- ADR-0383 D3. A customer-admitted third-party MCP client becomes a delegation grantee class of
-- its own. Existing rows are PARTY grants and keep exactly their meaning: the new type column
-- defaults to PARTY and the two binding columns are NULL for them.
--
-- An MCP_CLIENT grant records the client id (`azp`) and the DPoP key thumbprint (`cnf.jkt`) at
-- grant time, so the grant is looked up server-side from (grantor, client, key) and never from an
-- id a client supplies. The capability restriction (reads + propose-payment only) lives in the
-- aggregate, which is the only writer; the CHECK below pins the column shape so no writer can
-- store a half-bound MCP grant or a PARTY grant carrying client bindings.
--
-- Rollback: a new forward migration that drops the index, the constraints and the three columns,
-- after confirming no MCP_CLIENT rows exist (they would otherwise read back as PARTY grants).
-- Never edit this file after it has been applied.
ALTER TABLE delegation_grants
    ADD COLUMN grantee_type VARCHAR(16) NOT NULL DEFAULT 'PARTY',
    ADD COLUMN grantee_client_id VARCHAR(255),
    ADD COLUMN grantee_key_thumbprint VARCHAR(43);

ALTER TABLE delegation_grants
    ADD CONSTRAINT chk_delegation_grantee_type CHECK (grantee_type IN ('PARTY', 'MCP_CLIENT')),
    ADD CONSTRAINT chk_delegation_grantee_binding CHECK (
        (grantee_type = 'PARTY' AND grantee_client_id IS NULL AND grantee_key_thumbprint IS NULL)
        OR (grantee_type = 'MCP_CLIENT' AND grantee_client_id IS NOT NULL AND grantee_key_thumbprint IS NOT NULL)
    );

CREATE INDEX idx_delegation_mcp_client_binding
    ON delegation_grants (grantor_party_id, grantee_client_id, grantee_key_thumbprint)
    WHERE grantee_type = 'MCP_CLIENT';
