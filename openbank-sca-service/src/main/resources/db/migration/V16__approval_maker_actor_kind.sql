-- Additive maker provenance for durable four-eyes approvals (#11588). Existing records have no
-- verified kind; UNKNOWN is honest and is not inferred from the maker identifier. Old binaries
-- ignore the column and continue to insert rows through the default during a rolling rollout.
-- Rollback: retain the column and its evidence. Do not rewrite or discard live approvals; older
-- binaries can ignore the field until they are replaced.
ALTER TABLE sca_operator_approvals
    ADD COLUMN maker_actor_kind VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (maker_actor_kind IN ('HUMAN', 'AI_AGENT', 'SERVICE_ACCOUNT', 'CUSTOMER_PARTY', 'UNKNOWN'));
