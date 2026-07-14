-- ADR-0162 D7: make onboarding-document issuance idempotent + resumable at the data layer, so
-- at-least-once Kafka redelivery (or a crash between rendering the document and opening its
-- ceremony) can never produce a duplicate onboarding contract / duplicate ceremony, nor strand an
-- account with a document but no signature ceremony. Replaces the previous check-then-act
-- application scan (a TOCTOU race) with a real DB guarantee.
--
-- Rollback:
--   DROP INDEX IF EXISTS uq_signature_ceremonies_active_document;
--   DROP INDEX IF EXISTS uq_documents_idempotency_key;
--   ALTER TABLE documents DROP COLUMN IF EXISTS idempotency_key;

-- Onboarding-specific idempotency key ("onboarding:<accountId>"). NULL for every other document
-- type (ad-hoc renders via the REST API), so the partial unique index below constrains ONLY
-- onboarding documents and never the general case_ref column (which legitimately repeats across
-- document types for one case).
ALTER TABLE documents ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_documents_idempotency_key
    ON documents (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- At most one NON-TERMINAL ceremony per document: prevents two concurrent deliveries opening two
-- ceremonies for the same onboarding document, while still allowing a fresh ceremony to be opened
-- after a previous one reached a terminal DECLINED/EXPIRED state (a legitimate re-attempt).
CREATE UNIQUE INDEX IF NOT EXISTS uq_signature_ceremonies_active_document
    ON signature_ceremonies (document_id)
    WHERE status NOT IN ('DECLINED', 'EXPIRED');
