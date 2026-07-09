-- ADR-0143 phase 2e: fee reversal/refund. A wrongly-charged (already-POSTED) fee is reversed by a
-- compensating ledger journal, not by mutating the original assessed_fee row or its journal —
-- the original stays an immutable audit record; these columns only record the OUTCOME of the
-- reversal against it. `posting_status` gains REVERSAL_PENDING/REVERSED (see
-- com.openbank.billing.domain.PostingStatus); the CHECK constraint enumerates the full set since
-- Postgres ENUM ALTER TYPE ... ADD VALUE cannot run inside the same transaction as its first use
-- in some Postgres versions, so this migration widens billing_posting_status via the
-- add-new-type-and-swap pattern rather than in-place ADD VALUE (safe to run against a live DB with
-- existing rows: the swap preserves all existing values verbatim).
--
-- Rollback: the swap is reversible only if no row has yet taken a REVERSAL_PENDING/REVERSED value
-- (older code cannot represent them). If so:
--   ALTER TABLE assessed_fee DROP COLUMN reversal_journal_id, DROP COLUMN reversal_reason, DROP COLUMN reversed_at;
--   ALTER TABLE assessed_fee ALTER COLUMN posting_status TYPE VARCHAR(32);
--   DROP TYPE billing_posting_status;
--   CREATE TYPE billing_posting_status AS ENUM ('NOT_APPLICABLE', 'PENDING', 'POSTED', 'FAILED');
--   ALTER TABLE assessed_fee ALTER COLUMN posting_status TYPE billing_posting_status USING posting_status::billing_posting_status;

ALTER TYPE billing_posting_status RENAME TO billing_posting_status_old;

CREATE TYPE billing_posting_status AS ENUM (
    'NOT_APPLICABLE', 'PENDING', 'POSTED', 'FAILED', 'REVERSAL_PENDING', 'REVERSED'
);

ALTER TABLE assessed_fee
    ALTER COLUMN posting_status DROP DEFAULT,
    ALTER COLUMN posting_status TYPE billing_posting_status USING posting_status::text::billing_posting_status,
    ALTER COLUMN posting_status SET DEFAULT 'NOT_APPLICABLE';

DROP TYPE billing_posting_status_old;

-- Outcome of a reversal against this fee (null until a reversal is posted). reversal_journal_id
-- is the ledger's id for the COMPENSATING journal (distinct from journal_id, the original
-- charge's journal) — both are kept so the audit trail shows the full charge/reverse pair.
ALTER TABLE assessed_fee
    ADD COLUMN reversal_journal_id UUID,
    ADD COLUMN reversal_reason     VARCHAR(500),
    ADD COLUMN reversed_at         TIMESTAMPTZ;

CREATE INDEX idx_assessed_fee_posting_status_reversal
    ON assessed_fee(posting_status)
    WHERE posting_status IN ('REVERSAL_PENDING', 'REVERSED');
