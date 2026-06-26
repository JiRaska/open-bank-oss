-- Repair journal_lines mis-parented by the pre-fix JournalEntry.reverse() bug.
--
-- Before the fix in this release, reverse() copied each reversal line with a fresh id and a flipped
-- side but left journal_id pointing at the ORIGINAL entry. persistLines() therefore attached the
-- reversal lines to the original (giving it 2N lines) and saved the reversal entry with ZERO lines.
-- Reconstructing a line-less entry on the read path violates the `lines.size >= 2` domain invariant,
-- so GET /api/v1/journals returned HTTP 400 and the whole General Ledger view failed.
--
-- This re-parents the mis-attached reversal lines onto their reversal entries. It changes no monetary
-- value — only a structural FK (journal_id). ADR-0039 ledger immutability constrains posted *amounts*,
-- not the parentage of a row that was written to the wrong aggregate by a bug.
--
-- The affected rows cannot be identified by value alone: each original now holds two mirror pairs
-- (same account + amount, opposite side), so a value-based predicate would match the true lines too.
-- The reversal lines are therefore addressed by their known ids (captured from the live cluster). In
-- any environment that never hit the bug these ids do not exist, so every statement is a 0-row no-op.
--
-- Rollback: move the same four lines back to their original entries, i.e. set journal_id back to
--   'ec0fc7f3-0c46-4e84-9d24-aa772f188f61' for a67c9041…/55ba0d76… and
--   '415484e3-44d5-4cb5-bedf-478bd17a3906' for 1aa7e0f8…/1f7b70e6… .
--
-- Post-condition (verify after apply): each of entries #1–#4 has exactly 2 balanced lines, e.g.
--   select journal_id, count(*) from journal_lines
--   where journal_id in ('ec0fc7f3-…','415484e3-…','a859a6ff-…','d0b7c640-…') group by journal_id;
-- must return 2 for every row.

-- Reversal of entry #1: ec0fc7f3… (REVERSED original) -> a859a6ff… (POSTED reversal)
UPDATE journal_lines
SET    journal_id = 'a859a6ff-549a-43cf-b124-4d95d62f7d74'
WHERE  id IN ('a67c9041-2be3-47de-bacc-e907befd07bc', '55ba0d76-c389-4223-a6bd-b27e49ab39a9')
  AND  journal_id = 'ec0fc7f3-0c46-4e84-9d24-aa772f188f61';

-- Reversal of entry #2: 415484e3… (REVERSED original) -> d0b7c640… (POSTED reversal)
UPDATE journal_lines
SET    journal_id = 'd0b7c640-9d34-4c1c-b7c5-06759820ef19'
WHERE  id IN ('1aa7e0f8-e704-4d78-bf32-e6a7f77e3c0e', '1f7b70e6-644a-4a72-832e-843ed9e6402c')
  AND  journal_id = '415484e3-44d5-4cb5-bedf-478bd17a3906';
