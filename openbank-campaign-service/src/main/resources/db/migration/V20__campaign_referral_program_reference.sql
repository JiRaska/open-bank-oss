-- SPDX-License-Identifier: Apache-2.0
-- Immutable server-resolved MGM programme reference. Reward rules remain in referral-service.
-- Existing campaigns remain ordinary campaigns with all three columns NULL.
--
-- ROLLBACK: older binaries ignore these additive nullable columns. Drop the constraint and columns
-- only after no campaign needs its MGM reference and the audit record has been retained elsewhere.

ALTER TABLE campaigns ADD COLUMN referral_program_id UUID;
ALTER TABLE campaigns ADD COLUMN referral_program_name TEXT;
ALTER TABLE campaigns ADD COLUMN referral_program_version INTEGER;

ALTER TABLE campaigns ADD CONSTRAINT campaign_referral_program_ref_complete CHECK (
    (referral_program_id IS NULL AND referral_program_name IS NULL AND referral_program_version IS NULL)
    OR
    (referral_program_id IS NOT NULL AND referral_program_name IS NOT NULL AND referral_program_version >= 1)
);

CREATE INDEX idx_campaigns_referral_program_id ON campaigns (referral_program_id)
    WHERE referral_program_id IS NOT NULL;
