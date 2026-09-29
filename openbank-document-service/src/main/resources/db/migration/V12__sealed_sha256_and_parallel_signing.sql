-- 1. Digest of the stored PDF after the bank's PAdES seal is applied at ceremony completion.
--    documents.sha256 keeps the digest the signers approved over SCA (dynamic linking); sealing
--    rewrites the stored bytes, so a verifier needs both values. Nullable: unsigned documents and
--    documents sealed before this column existed have no recorded sealed digest.
-- 2. Parallel signing: a company's joint representatives sign a business agreement in any order.
--    Defaults to false, so every existing (retail) ceremony keeps its strict signer order.
--
-- Rollback:
--   ALTER TABLE signature_ceremonies DROP COLUMN IF EXISTS parallel_signing;
--   ALTER TABLE documents DROP COLUMN IF EXISTS sealed_sha256;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS sealed_sha256 VARCHAR(64);
ALTER TABLE signature_ceremonies ADD COLUMN IF NOT EXISTS parallel_signing BOOLEAN NOT NULL DEFAULT FALSE;
