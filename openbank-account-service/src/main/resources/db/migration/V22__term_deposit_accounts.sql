-- Add the account representation of the TERM_DEPOSIT product already supported by product-catalog.
-- Rollback: first close/migrate TERM_DEPOSIT accounts, then restore the previous check constraint.
ALTER TABLE accounts DROP CONSTRAINT chk_accounts_type;

ALTER TABLE accounts ADD CONSTRAINT chk_accounts_type CHECK (account_type IN (
    'CURRENT','SAVINGS','TERM_DEPOSIT','NOSTRO','GL_ASSET','GL_LIABILITY','GL_INCOME','GL_EXPENSE'
));
