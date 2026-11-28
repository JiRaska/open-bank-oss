-- A scoped host registration cannot redeem or rotate a different client's grant.
-- NULL retains the original vendor registration and supports prior writers during rollout.
-- Rollback: Disable the scoped registration and roll back the application while retaining this column.
-- Remove the column only in a later forward migration after revoking all scoped token families.
ALTER TABLE mcp_hosted_vendor_families
    ADD COLUMN scoped_client_id text;
