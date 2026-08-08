-- ADR-0232 D5 (#2990 AC10): the grantor transparency query — "what did my delegate do with my
-- account". The facts already arrive inside the chain-hashed `payload` column (customer-edge
-- flattens its audit details into the event JSON, so `onBehalfOf` and `delegationId` are top-level
-- fields there); these are QUERY INDEXES derived from it, exactly like channel/act_chain in V9.
--
-- Deliberately NOT part of the chain hash. The hash covers sha256(payload), so a tampered
-- onBehalfOf inside the event JSON already breaks the chain; recomputing every pre-V10 link to
-- fold in two nullable columns would make the whole history unverifiable to buy nothing. The
-- consequence, stated plainly: an in-place UPDATE of these two COLUMNS alone is invisible to
-- `verifyChain`. They are a lookup path, never evidence — a responder answering "was this really
-- delegated" reads `payload`, which is chained.
-- Rollback: DROP INDEX IF EXISTS idx_audit_on_behalf_of; ALTER TABLE audit_entries DROP COLUMN on_behalf_of, DROP COLUMN delegation_id;
ALTER TABLE audit_entries
    ADD COLUMN IF NOT EXISTS on_behalf_of VARCHAR(64),
    ADD COLUMN IF NOT EXISTS delegation_id VARCHAR(64);

-- Partial: delegated actions are a small minority of the log, and the grantor query is the only
-- reader. Ordered by occurred_at DESC to match the query's own ordering.
CREATE INDEX IF NOT EXISTS idx_audit_on_behalf_of
    ON audit_entries (on_behalf_of, occurred_at DESC)
    WHERE on_behalf_of IS NOT NULL;
