-- ADR-0377 D3: payee_history is keyed by payee_identifier, a VARCHAR(64) sized for a UUID string.
-- The keyed counterparty pseudonym is `cp2:<keyId>:<64 hex>` (up to 101 characters), so it would
-- not fit. Widening a varchar is metadata-only in Postgres (no table rewrite, no index rebuild);
-- the composite primary key (account_id, payee_identifier) already serves the lookup, so no new
-- index is needed. Existing UUID-keyed rows are untouched and keep matching the legacy path.
--
-- Data classification: a `cp2:` value is an HMAC-SHA-256 of the payee's account identifier under
-- a secret held only by fraud-service. That is a keyed PSEUDONYM, still personal data under GDPR
-- (re-identifiable by whoever holds the key). The raw identifier is never stored here.
--
-- Rollback (before apply only — never edit an applied migration):
--   DELETE FROM payee_history WHERE length(payee_identifier) > 64;
--   ALTER TABLE payee_history ALTER COLUMN payee_identifier TYPE VARCHAR(64);
-- The DELETE discards every pseudonym-keyed history row, so those payees read as new again.

ALTER TABLE payee_history ALTER COLUMN payee_identifier TYPE VARCHAR(128);
