-- ADR-0334 S8: bind each stored Idempotency-Key response to the REQUEST it answered.
-- request_hash = RequestFingerprint (SHA-256 of method, path and the key-sorted JSON body). The
-- same key with a different body is refused 409 IDEMPOTENCY_KEY_REUSED instead of replaying the
-- first response. Nullable: rows stored before this migration carry none and replay as before.
--
-- Rollback: ALTER TABLE pension_idempotency_records DROP COLUMN request_hash; (the replay filter
-- must be rolled back with it — it selects and inserts the column).
ALTER TABLE pension_idempotency_records ADD COLUMN request_hash CHAR(64);
