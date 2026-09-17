-- Expand-only index for company-scoped ACCEPT keyset pages. Existing ISSUE readers and writers
-- remain on V30's index. Deploy this before enabling the acceptance inbox route.
-- Rollback: disable the new route and retain this harmless index; drop only via a separately
-- reviewed forward migration after all readers have stopped using the acceptance inbox.
CREATE INDEX idx_delegation_statutory_acceptance_inbox_page
    ON delegation_statutory_operations
       (principal_party_id, rule_hash, created_at DESC, operation_id DESC)
    WHERE state = 'PENDING' AND operation_kind = 'ACCEPT';
