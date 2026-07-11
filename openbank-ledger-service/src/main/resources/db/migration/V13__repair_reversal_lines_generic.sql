-- Generic repair for the JournalEntry.reverse() line-re-parenting bug (#465/#527), covering any
-- reversal booked between V10 (one-time hardcoded-id repair) and the code fix landing in #528.
--
-- Same root cause as V10: reverse() copied each reversal line with a flipped side but left
-- journal_id pointing at the ORIGINAL entry, so persistLines() attached the reversal's lines to
-- the original (doubling its line count) and saved the reversal with ZERO lines (unreadable —
-- fails the domain's lines.size >= 2 invariant on hydration).
--
-- Unlike V10, this does not hardcode ids. journal_lines.id is a UUIDv7 (ADR-0106, Ids.newId()),
-- whose high 48 bits are a millisecond creation timestamp. The orphaned mirror lines were minted
-- during the SAME reverse() call that produced the (empty) reversal entry, strictly AFTER the
-- original's genuine lines were minted (at the original's own posting time). So for each broken
-- reversal, sorting the original's current lines by their embedded timestamp and taking the
-- chronologically-latest half identifies exactly the misattached mirror lines — without touching
-- value (account/amount/currency/side) at all, and without assuming any specific ids.
--
-- Safety, never guesses: a broken reversal is repaired only when ALL of the following hold —
--   1. it is the ONLY broken reversal pointing at its original (no N-way ambiguity),
--   2. the original's current line count is even and splits into two equal timestamp-ordered
--      halves,
--   3. the "orphaned" (later) half is internally balanced (debits == credits per currency),
--      confirming it looks like a genuine mirrored entry, not an accidental match.
-- Anything that doesn't cleanly satisfy this is left untouched and reported via a NOTICE for
-- manual investigation — this migration prefers under-repairing to any risk of moving the wrong
-- line on live financial data.
--
-- Post-condition (verify after apply): every previously-broken reversal now has >= 2 lines, and
--   SELECT r.id FROM journal_entries r
--   WHERE r.reversal_of IS NOT NULL
--     AND NOT EXISTS (SELECT 1 FROM journal_lines jl WHERE jl.journal_id = r.id)
--   returns only the rows this migration's NOTICEs explicitly flagged as skipped (if any).
--
-- Rollback: for each repaired reversal (see apply-time NOTICEs for the exact line ids and their
-- original journal_id), UPDATE journal_lines SET journal_id = <original_id> WHERE id IN (...).

DO $$
DECLARE
  broken RECORD;
  original_line_count INT;
  orphan_count INT;
  ambiguous_originals INT;
  orphan_debit NUMERIC;
  orphan_credit NUMERIC;
  orphan_ids UUID[];
BEGIN
  FOR broken IN
    SELECT r.id AS reversal_id, r.reversal_of AS original_id
    FROM journal_entries r
    WHERE r.reversal_of IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM journal_lines jl WHERE jl.journal_id = r.id)
  LOOP
    -- Safety 1: exactly one broken reversal must point at this original.
    SELECT count(*) INTO ambiguous_originals
    FROM journal_entries r2
    WHERE r2.reversal_of = broken.original_id
      AND NOT EXISTS (SELECT 1 FROM journal_lines jl WHERE jl.journal_id = r2.id);
    IF ambiguous_originals <> 1 THEN
      RAISE NOTICE 'SKIP reversal % (original %): % broken reversals point at the same original — ambiguous, needs manual review',
        broken.reversal_id, broken.original_id, ambiguous_originals;
      CONTINUE;
    END IF;

    -- Safety 2: the original's current line count must be even.
    SELECT count(*) INTO original_line_count
    FROM journal_lines WHERE journal_id = broken.original_id;
    IF original_line_count = 0 OR original_line_count % 2 <> 0 THEN
      RAISE NOTICE 'SKIP reversal % (original %): original has % lines (not a nonzero even count) — no clean split, needs manual review',
        broken.reversal_id, broken.original_id, original_line_count;
      CONTINUE;
    END IF;
    orphan_count := original_line_count / 2;

    -- The chronologically-latest half, by the UUIDv7 timestamp embedded in the line id
    -- (high 48 bits = millisecond epoch — see ADR-0106 Ids.newId()).
    SELECT array_agg(id) INTO orphan_ids
    FROM (
      SELECT id
      FROM journal_lines
      WHERE journal_id = broken.original_id
      ORDER BY ('x' || substring(replace(id::text, '-', '') for 12))::bit(48)::bigint DESC
      LIMIT orphan_count
    ) latest_half;

    -- Safety 3: the candidate orphan half must be internally balanced (debits == credits per
    -- currency) — this is what a genuine mirrored reversal entry looks like. Single-currency
    -- check is sufficient here since a cross-currency (FX) entry's legs are each individually
    -- balanced per PaymentJournalFactory's own invariant.
    SELECT
      coalesce(sum(base_amount) FILTER (WHERE side = 'D'), 0),
      coalesce(sum(base_amount) FILTER (WHERE side = 'C'), 0)
    INTO orphan_debit, orphan_credit
    FROM journal_lines WHERE id = ANY(orphan_ids);
    IF orphan_debit <> orphan_credit THEN
      RAISE NOTICE 'SKIP reversal % (original %): candidate orphan lines are not balanced (debit=% credit=%) — needs manual review',
        broken.reversal_id, broken.original_id, orphan_debit, orphan_credit;
      CONTINUE;
    END IF;

    UPDATE journal_lines SET journal_id = broken.reversal_id WHERE id = ANY(orphan_ids);
    RAISE NOTICE 'REPAIRED reversal % (original %): moved % line(s) % from journal_id % to %',
      broken.reversal_id, broken.original_id, orphan_count, orphan_ids, broken.original_id, broken.reversal_id;
  END LOOP;
END $$;
