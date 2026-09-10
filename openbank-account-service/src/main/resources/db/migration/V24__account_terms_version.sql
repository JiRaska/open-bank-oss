-- #9044: record which terms version a term deposit was opened under.
--
-- The terms carry the early-withdrawal penalty and the notice period — the two clauses a
-- customer is held to and the two most likely to be revised. Without this record, "which terms
-- govern this deposit" could only be reconstructed from openedAt against catalogue effective
-- dates, which assumes the catalogue is never corrected in place and produces no evidence of
-- what the customer was shown. A complaints/conduct review asks this question first, months
-- after opening — exactly when reconstruction is least defensible.
--
-- We store the version REFERENCE plus the document url/effectiveFrom as snapshot metadata, not
-- the document itself (duplicating the catalogue would drift). This makes catalogue version
-- retention a REQUIREMENT, recorded here explicitly: a terms version referenced by any open or
-- closed account must stay retrievable in product-catalog forever.
--
-- Existing term deposits stay NULL — they have no truthful record and a backfill from openedAt
-- would manufacture evidence (#9044 decision 3, same reasoning as CourtRegisterSignalState
-- .NOT_CONFIGURED, #6646). The CHECK is therefore NOT VALID: it constrains rows written AFTER
-- this migration and never audits the honest-null historical ones.
--
-- Rollback: ALTER TABLE accounts DROP CONSTRAINT IF EXISTS chk_accounts_terms;
--           ALTER TABLE accounts DROP COLUMN IF EXISTS terms_version,
--                          DROP COLUMN IF EXISTS terms_url, DROP COLUMN IF EXISTS terms_effective_from;

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS terms_version TEXT;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS terms_url TEXT;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS terms_effective_from DATE;

ALTER TABLE accounts ADD CONSTRAINT chk_accounts_terms
    CHECK (account_type <> 'TERM_DEPOSIT' OR terms_version IS NOT NULL) NOT VALID;
