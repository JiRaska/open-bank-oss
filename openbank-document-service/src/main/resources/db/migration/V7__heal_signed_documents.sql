-- Heal documents that were signed for real but never recorded as SIGNED.
--
-- Until the fix that shipped alongside this migration, SignatureCeremonyService.sealDocument()
-- applied the institutional seal to the stored PDF but never persisted the document's own
-- GENERATED/PENDING_SIGNATURE -> SIGNED transition. Document.markSigned() existed and was unit
-- tested, but had no call site. So a completed ceremony left behind a contradiction that no code
-- path could ever resolve:
--
--   signature_ceremonies.status = 'COMPLETED'  (the client really did sign, SCA-verified)
--   documents.status            = 'GENERATED'  (…but the bank never wrote it down)
--
-- That contradiction is a deadlock, not a cosmetic drift. OnboardingDocumentService
-- .ensureOnboardingAgreement returns early ONLY on documents.status = 'SIGNED', so an affected
-- client was handed the same unsigned agreement on every login — and could not sign their way out
-- of it, because SignatureCeremony.recordDecision() rejects any decision on a ceremony that is
-- already COMPLETED. Sign screen forever; an active client locked out of their own bank.
--
-- Healing here is a status correction, not a signature: sealDocument() stored the sealed bytes
-- BEFORE the missing transition, so the PDF on these rows is already sealed and the ceremony
-- already holds the SCA-verified per-signer evidence (signers_json.signedAt). The only thing this
-- statement writes is the fact the bank failed to record.
--
-- Scope is deliberately narrow:
--   - COMPLETED ceremonies only — never DECLINED/EXPIRED/PENDING/PARTIALLY_SIGNED, where no
--     signature act finished and 'SIGNED' would be a fabrication.
--   - GENERATED/PENDING_SIGNATURE documents only — the exact two states the missing transition
--     could strand a document in. ARCHIVED rows are left untouched: they were superseded before
--     signing (a language switch, ADR-0169 D3), are already excluded from the agreement lookup,
--     and resurrecting one to SIGNED would assert a contract the client never signed.
--
-- Rollback: none. This does not create or destroy legal state — it records state that already
-- exists in signature_ceremonies. Reverting it would re-strand the same clients.

UPDATE documents d
SET status = 'SIGNED'
FROM signature_ceremonies c
WHERE c.document_id = d.id
  AND c.status = 'COMPLETED'
  AND d.status IN ('GENERATED', 'PENDING_SIGNATURE');
