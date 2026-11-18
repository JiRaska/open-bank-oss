-- Evidence-chain hash version (#16473, #16511) — same scheme as audit-service's V-migration for
-- #3586 (ADR-0133 precedent).
--
-- Rows already in dispute_evidence were stamped under one of two earlier canonical forms that
-- cannot be recomputed from the stored row (the first hashed a nanosecond/offset timestamp string
-- the TIMESTAMPTZ column cannot give back; the second omitted the evidence id). They are left
-- exactly as written: tamper-evidence is never rewritten or re-stamped. NULL marks them legacy,
-- and EvidenceChain.verify reports them as such instead of claiming to have verified them.
-- Every row written after this migration carries the version, and the version is itself hashed.
--
-- No backfill, no default: a DEFAULT would stamp the legacy rows with a version they were not
-- hashed under, which is exactly the false claim this column exists to prevent.
--
-- Rollback (before apply only — never edit an applied migration):
--   ALTER TABLE dispute_evidence DROP COLUMN IF EXISTS hash_version;
-- After apply, dropping the column makes every versioned row unverifiable — do not.

ALTER TABLE dispute_evidence ADD COLUMN hash_version SMALLINT NULL;
