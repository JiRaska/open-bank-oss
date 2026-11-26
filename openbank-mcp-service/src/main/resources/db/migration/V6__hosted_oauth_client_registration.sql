-- A scoped host registration cannot redeem or rotate a different client's grant.
-- NULL retains the original vendor registration and supports prior writers during rollout.
ALTER TABLE mcp_hosted_vendor_families
    ADD COLUMN scoped_client_id text;
