-- A journal entry may be reversed at most once (#465 concurrency sweep).
--
-- Backstop for the transactional status guard in PanacheJournalRepository.saveReversal:
-- even if application code regresses, a second reversal row pointing at the same original
-- cannot commit. journal_entries is partitioned by entry_date, so the unique index must
-- include the partition key; a reversal always inherits the original's entry_date
-- (JournalEntry.reverse preserves it), so (reversal_of, entry_date) is equivalent to
-- uniqueness on reversal_of alone.
--
-- Rollback: DROP INDEX uq_journal_entries_reversal_of;
CREATE UNIQUE INDEX uq_journal_entries_reversal_of
    ON journal_entries (reversal_of, entry_date)
    WHERE reversal_of IS NOT NULL;
