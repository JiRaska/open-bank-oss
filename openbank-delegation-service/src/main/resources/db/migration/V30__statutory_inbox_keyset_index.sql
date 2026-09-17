-- Expand-only index for the company-scoped keyset inbox. Existing readers and writers keep
-- using V28 evidence unchanged; deploy this before enabling the paged customer route.
-- Rollback: revert the application route and retain this harmless index. Drop it only through
-- a separately reviewed forward migration after confirming no readers use the paged inbox.
CREATE INDEX idx_delegation_statutory_inbox_page
    ON delegation_statutory_operations
       (principal_party_id, rule_hash, created_at DESC, operation_id DESC)
    WHERE state = 'PENDING';
