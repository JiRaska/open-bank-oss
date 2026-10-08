-- Preserve the exact create result across later draft edits. Existing drafts have no
-- recoverable original response after replacement. Revision-0 rows can be reconstructed;
-- the application saves that reconstruction before their first replacement.
-- Rollback: retain this column and its snapshots. Disable legacy draft writes while
-- running an older application version, then restore the snapshot-aware version.
ALTER TABLE business_payment_batch_drafts ADD COLUMN original_response_json TEXT;
