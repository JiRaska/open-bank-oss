-- A committed claim precedes the pacs.008 POST. If the response is lost, retain UNKNOWN and
-- forbid a second send until a scheme-side status decision is reconciled. Before applying this
-- migration in a rolling deployment, disable scheme submission and drain old Temporal workers:
-- old code cannot honor this fence. Every historical VALIDATED row is conservatively fenced,
-- because the database cannot tell whether an earlier scheme POST reached the counterparty.
-- Those rows need scheme-side reconciliation before a payment can leave the hold.
-- Rollback after rolling back the code: ALTER TABLE sepa_payments DROP COLUMN scheme_outcome_unknown;
ALTER TABLE sepa_payments ADD COLUMN scheme_outcome_unknown BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE sepa_payments SET scheme_outcome_unknown = TRUE WHERE status = 'VALIDATED';
