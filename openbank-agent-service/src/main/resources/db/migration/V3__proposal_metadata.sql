-- ADR-0259: immutable, non-secret provenance for review proposals. Additive expansion only:
-- older binaries omit the column and the default preserves their INSERTs. Rollback: retain this
-- column; a prior binary ignores it and continues to read/write agent_proposal safely.
ALTER TABLE agent_proposal
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
