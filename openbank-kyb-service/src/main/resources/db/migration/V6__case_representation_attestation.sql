-- #10247: retain the exact rule confirmation that set a business case's signing quorum.
-- Existing cases stay NULL and cannot acquire statutory JOINT authority by inference.
-- Rollback: disable new statutory-policy writes first; retain this nullable evidence column
-- while old and new images may overlap. Drop only after those readers are retired and
-- separately authorised archival of any referenced attestations.
ALTER TABLE kyb_cases ADD COLUMN representation_attestation_id UUID;
