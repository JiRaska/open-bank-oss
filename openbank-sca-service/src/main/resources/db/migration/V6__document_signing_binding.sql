-- Document-bound SCA dynamic linking (ADR-0169 D2): a DOCUMENT_SIGNING challenge's device-signed
-- payload binds to a specific document content hash + signature ceremony, not amount/payee. Both
-- columns are nullable — every pre-existing purpose (payment/login/consent/...) leaves them null.
-- Rollback: DROP COLUMN dynamic_document_sha256; DROP COLUMN dynamic_ceremony_id; (no data loss for
-- rows written before this migration — the columns are additive and nullable).
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_document_sha256 VARCHAR(64);
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_ceremony_id VARCHAR(255);
