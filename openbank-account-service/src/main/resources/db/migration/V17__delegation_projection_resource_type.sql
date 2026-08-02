-- Savings-goal delegation: resource typing on the delegation projection (ADR-0232 D3).
-- A savings goal is account metadata (ADR-0153), so SAVINGS_GOAL grants share this
-- table keyed by account id; the column keeps ACCOUNT and SAVINGS_GOAL rows apart on
-- the same account (existing rows backfill to 'ACCOUNT').
-- Rollback: ALTER TABLE account_delegation_projection DROP COLUMN resource_type;
ALTER TABLE account_delegation_projection
    ADD COLUMN resource_type VARCHAR(50) NOT NULL DEFAULT 'ACCOUNT';

CREATE INDEX idx_delegation_projection_type_guard
    ON account_delegation_projection(account_id, grantee_party_id, resource_type, active);
