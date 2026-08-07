-- Records WHICH canonical form a row's `record_hash` was computed with, so a corrected
-- canonicalisation does not silently invalidate the rows that predate it.
--
-- WHY THIS EXISTS. chainHash() hashed `Instant.toString()`, which renders NANOSECOND precision,
-- while `timestamptz` stores MICROSECONDS. Every hash was therefore computed over a value the
-- database immediately truncated, and on read-back the recomputation could never match. The chain
-- had verified ZERO links since it shipped: `openbank_audit_chain_entries_checked` read 0 against
-- 1066 chained rows, and `AuditChainBroken` was a permanent critical about a chain nobody had
-- tampered with.
--
-- Proven rather than inferred (2026-08-03): taking the newest chained row and brute-forcing the
-- 10^6 sub-microsecond combinations Postgres had discarded reproduced its stored record_hash
-- exactly, at nanos (129, 759).
--
-- WHY A COLUMN AND NOT A BACKFILL. The pre-fix rows can never be verified — their original
-- nanoseconds are gone. The three options were not equivalent:
--   * re-chaining them rewrites audit hashes wholesale, which destroys tamper-evidence for the
--     whole period and is indistinguishable from what an attacker would do;
--   * folding them into the pre-V5 `unchained` bucket is dishonest, because those rows DO carry a
--     hash and are unverifiable for an entirely different reason;
--   * recording the boundary explicitly keeps the metric truthful about the segment it can
--     actually check, and says so in its own counter.
-- This takes the third. NULL means "hashed with the pre-fix canonical form": unverifiable, counted
-- separately, never reported as either verified or tampered.
--
-- Rollback: ALTER TABLE audit_entries DROP COLUMN hash_version;
--   (safe — no row's record_hash is modified by this migration, and verifyChain treats a missing
--   column the same way it treats NULL: legacy, not broken.)
ALTER TABLE audit_entries ADD COLUMN IF NOT EXISTS hash_version SMALLINT;

COMMENT ON COLUMN audit_entries.hash_version IS
  'Canonical form used for record_hash. NULL = pre-2026-08-03 form (nanosecond Instant.toString(), '
  'unverifiable because timestamptz truncates to microseconds). 2 = microsecond-truncated form.';

-- Partial index: verifyChain scans for the first verifiable row on every run, and the legacy
-- segment is expected to stay a fixed size forever while the verifiable one grows.
CREATE INDEX IF NOT EXISTS idx_audit_entries_hash_version
  ON audit_entries(id ASC) WHERE hash_version IS NOT NULL;
