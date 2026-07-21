-- Data repair for the applyFrom idempotency-key bug (DocumentRepositoryImpl):
-- Document.archive() nulled the key in the domain, but the entity update never copied it, so
-- ARCHIVED rows kept their idempotency_key. Under the partial unique index
-- (uq_documents_idempotency_key WHERE idempotency_key IS NOT NULL) that key stayed taken, so the
-- next ensureOnboardingAgreement re-render hit a duplicate and fell back to the ARCHIVED document —
-- whose ceremony then rejected signing with "Only PENDING_SIGNATURE documents can be signed".
--
-- An ARCHIVED document is never resolved by idempotency key again (design: the key belongs to the
-- live, supersedable agreement), so releasing it here is safe and unblocks every party currently
-- stuck mid-onboarding. The code fix stops the key from being retained on future archives.
UPDATE documents
SET idempotency_key = NULL
WHERE status = 'ARCHIVED'
  AND idempotency_key IS NOT NULL;
