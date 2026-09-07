-- ADR-0252 phase 1 (#8615, refs #4348): carry the synthetic-origin taint on the ledger's own
-- state, not only on the outbox.
--
-- V24 put `synthetic` on ledger_outbox, so the taint survived onto Kafka and died in the book of
-- record. The regulatory returns are built from the JOURNAL (finrep reads the trial balance, not
-- the event stream), so a canary posting was already summed into the same balances as real
-- customer money with no dimension to exclude it by.
--
-- The column sits on journal_entries, NOT journal_lines: a journal is balanced within itself
-- (JournalEntry.validateBalance), so excluding whole entries keeps debits == credits, while a
-- per-line filter could split a balanced entry and silently unbalance every aggregate that used it.
--
-- journal_entries is RANGE-partitioned by entry_date; ALTER on the parent adds the column to every
-- existing partition and to every partition JournalPartitionMaintainer creates later.
--
-- DEFAULT FALSE is the fail-to-real direction argued in SyntheticTaint: every row already written
-- is real, and a wrongly-synthetic row would silently drop real customer money out of a regulatory
-- return.
--
-- Rollback: before this migration is applied,
--   DROP INDEX IF EXISTS idx_journal_entries_synthetic;
--   ALTER TABLE journal_entries DROP COLUMN synthetic;
-- Never edit an applied Flyway migration — the checksum covers the whole file, comments included.

ALTER TABLE journal_entries ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN journal_entries.synthetic IS
    'ADR-0252: TRUE when posted by a bank-owned synthetic (canary) customer. Excluded from the '
    'regulatory aggregates (trial balance defaults to real-only) and from NOTHING else — screening, '
    'VoP, SCA and limits always run for synthetic traffic.';

-- Partial index: synthetic rows are a tiny minority, so this serves the SYNTHETIC_ONLY selector
-- (and the canary-activity reconciliation behind it) without adding a full-width index that the
-- real-only path — already pruned by partition and entry_date — does not need.
CREATE INDEX idx_journal_entries_synthetic ON journal_entries (entry_date) WHERE synthetic;
