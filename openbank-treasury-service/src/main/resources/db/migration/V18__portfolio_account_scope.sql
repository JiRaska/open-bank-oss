-- Each configured custodian account contributes a current statement to the entity's portfolio.
-- Existing statement rows already identify their account; no holding or evidence is rewritten.
ALTER TABLE portfolio_statements DROP CONSTRAINT uq_portfolio_statement_version;
ALTER TABLE portfolio_statements ADD CONSTRAINT uq_portfolio_statement_version
    UNIQUE (entity, safekeeping_account, statement_date, version);
DROP INDEX uq_portfolio_statement_current;
CREATE UNIQUE INDEX uq_portfolio_statement_current
    ON portfolio_statements (entity, safekeeping_account, statement_date) WHERE superseded_by IS NULL;
-- Rollback requires stopping writes and retaining multi-account evidence before restoring the old
-- entity/date uniqueness. Refuse rollback while multiple account histories share an entity/date;
-- do not delete holdings or silently select one account to satisfy the old constraints.
