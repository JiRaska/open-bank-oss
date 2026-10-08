-- Preserve the exact create result across later draft edits. Existing drafts have no
-- recoverable original response; their create-key retries must fail closed.
-- Rollback: disable create traffic, then DROP COLUMN original_response_json.
ALTER TABLE business_payment_batch_drafts ADD COLUMN original_response_json TEXT;
