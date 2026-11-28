-- ADR-0385 D1: an authenticated hosted client may receive a grant without a DPoP thumbprint.
-- Require the approved connection on unbound grants and retain the existing binding on DPoP grants.
-- Rollback: revoke and remove grants with a null thumbprint under the applicable retention policy,
-- then restore chk_delegation_grantee_binding with the exact V34 predicate in a forward migration.
ALTER TABLE delegation_grants
    DROP CONSTRAINT chk_delegation_grantee_binding,
    ADD CONSTRAINT chk_delegation_grantee_binding CHECK (
        (grantee_type = 'PARTY' AND grantee_client_id IS NULL AND grantee_key_thumbprint IS NULL)
        OR (
            grantee_type = 'MCP_CLIENT'
            AND grantee_client_id IS NOT NULL
            AND (grantee_key_thumbprint IS NOT NULL OR mcp_connection_id IS NOT NULL)
        )
    );

-- Hosted PAR has confidential client authentication in place of a proof thumbprint.
-- Rollback: retire hosted requests after their connections are revoked and removed, then
-- restore NOT NULL in a forward migration. Keep the V40 length and character check.
ALTER TABLE mcp_connect_requests ALTER COLUMN par_jkt DROP NOT NULL;
