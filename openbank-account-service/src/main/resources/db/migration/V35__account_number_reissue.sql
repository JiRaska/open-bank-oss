-- SPDX-License-Identifier: Apache-2.0
-- Audit trail of account numbers re-issued because the stored CZ IBAN fails the ČNB mod-11 check
-- (decree 169/2011 Sb.). An early generator produced 24-char IBANs with a valid mod-97 but an
-- arbitrary BBAN; domestic-payment now rejects those terminally (AC01), so the affected accounts
-- get a new number from IbanGenerator (AccountNumberReissueService). One row per re-issue: the old
-- number, the new one, and when. The UNIQUE on old_account_number makes a second re-issue of the
-- same number fail inside the transaction that would have written it, so a canary overlap of two
-- pods cannot re-issue one account twice.
-- Rollback: DROP TABLE account_number_reissue; (schema only; restore the numbers first, see below)
--
-- The schema change itself is additive. The DATA change it records is not undone by a rollback of
-- the binary: an earlier binary reads accounts.account_number as before and simply sees the new
-- number. To roll back the numbers too (sandbox only — the old numbers are nationally invalid):
--   UPDATE accounts a SET account_number = r.old_account_number
--     FROM account_number_reissue r WHERE r.account_id = a.id AND a.account_number = r.new_account_number;
--   DROP TABLE account_number_reissue;
-- and set openbank.account.number-reissue.enabled=false first, or the job re-issues them again.

CREATE TABLE account_number_reissue (
    id                 UUID PRIMARY KEY,
    account_id         UUID        NOT NULL REFERENCES accounts (id),
    old_account_number VARCHAR(34) NOT NULL UNIQUE,
    new_account_number VARCHAR(34) NOT NULL UNIQUE,
    reason             VARCHAR(64) NOT NULL,
    reissued_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_account_number_reissue_account_id ON account_number_reissue (account_id);
