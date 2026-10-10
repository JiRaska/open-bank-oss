-- Bind every accepted key, including aliases of a byte-identical current statement.
-- A new statement and its original key commit atomically; aliases keep referencing their
-- accepted version after subsequent corrections. Existing original keys retain their meaning.
-- Rollback: DROP TABLE portfolio_statement_keys;
-- Original keys remain in portfolio_statements; rollback loses protection for accepted aliases.
CREATE TABLE portfolio_statement_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    statement_uuid UUID NOT NULL REFERENCES portfolio_statements (statement_uuid)
);

INSERT INTO portfolio_statement_keys (idempotency_key, statement_uuid)
    SELECT idempotency_key, statement_uuid FROM portfolio_statements;
