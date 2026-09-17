-- Digest of the stored PDF after the bank's PAdES seal is applied at ceremony completion.
-- documents.sha256 keeps the digest the signers approved over SCA (dynamic linking); sealing
-- rewrites the stored bytes, so a verifier needs both values. Nullable: unsigned documents and
-- documents sealed before this column existed have no recorded sealed digest.
--
-- Rollback:
--   ALTER TABLE documents DROP COLUMN IF EXISTS sealed_sha256;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS sealed_sha256 VARCHAR(64);
