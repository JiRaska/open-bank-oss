-- #10281: business approval co-signatures + attribution of the deciding party.
--
-- dynamic_approval_request_id / dynamic_payload_sha256: an APPROVAL challenge's device-signed
--   payload binds to one approval request and the SHA-256 of its frozen payload (dynamic linking),
--   so a co-signature can never be spent on another approval or on an edited instruction.
-- on_behalf_of_party_id: the entity the human acted for (X-Acting-For) — context only; the
--   challenge still belongs to party_id, the human.
-- decided_by_party_id / decided_by_credential_id: whose enrolled device decided the challenge,
--   written when the decision resolves it (item 3), so the row alone answers "who approved".
--
-- All columns are additive and nullable: every existing row and every other purpose leaves them
-- null, so there is no backfill and the previous release keeps working against this schema.
-- Rollback: ALTER TABLE sca_challenges DROP COLUMN dynamic_approval_request_id,
--   DROP COLUMN dynamic_payload_sha256, DROP COLUMN on_behalf_of_party_id,
--   DROP COLUMN decided_by_party_id, DROP COLUMN decided_by_credential_id;
--   (loses only the attribution/linking written since this migration; roll the app back first).
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_approval_request_id VARCHAR(64);
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_payload_sha256 VARCHAR(64);
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS on_behalf_of_party_id UUID;
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS decided_by_party_id UUID;
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS decided_by_credential_id VARCHAR(255);
