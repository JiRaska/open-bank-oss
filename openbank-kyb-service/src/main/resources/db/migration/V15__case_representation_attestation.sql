-- Preserve the exact attestation used by the case. Historical cases remain unpinned.
-- Rollback: ALTER TABLE kyb_cases DROP COLUMN representation_attestation_id;
ALTER TABLE kyb_cases ADD COLUMN representation_attestation_id UUID;

-- Rollback: ALTER TABLE kyb_representation_attestations DROP COLUMN confirmed_mode;
ALTER TABLE kyb_representation_attestations
    ADD COLUMN confirmed_mode VARCHAR(16) CHECK (confirmed_mode IN ('SOLE', 'JOINT_N', 'JOINT_ALL'));
