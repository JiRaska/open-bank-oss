-- Tamper-evident hash chain: record_hash = SHA-256(prev_hash | evidential fields).
-- The V2 no-update/no-delete rules stop casual mutation; the chain makes any mutation
-- that bypasses them (rule dropped, direct file edit, row re-insert) DETECTABLE via
-- GET /api/v1/audit/integrity. Rows older than this migration stay unchained (reported,
-- not verifiable) — the chain starts at the first row written after deploy.
ALTER TABLE audit_entries ADD COLUMN IF NOT EXISTS prev_hash   CHAR(64);
ALTER TABLE audit_entries ADD COLUMN IF NOT EXISTS record_hash CHAR(64);
