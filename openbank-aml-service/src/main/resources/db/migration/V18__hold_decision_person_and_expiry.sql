-- Four-eyes on a held payment's case compares PERSONS, not accounts (libs StaffIdentity): one
-- natural person with two staff accounts must not be both proposer and confirmer. The proposal
-- also gets a bounded lifetime and can be withdrawn by its proposer.
--   hold_proposer_person_id      the proposer's person id (token claim person_id)
--   hold_proposal_expires_at     after this the proposal cannot be confirmed; a new one may replace it
--   hold_proposal_withdrawn_by   account that withdrew it (always the proposer's person)
--   hold_proposal_withdrawn_at   when
-- Nullable, no default: metadata-only on Postgres. A proposal row without a person id cannot be
-- confirmed by this release (fails closed); its proposer makes it again.
--
-- Rollback: roll the code back first, then
--   ALTER TABLE aml_cases DROP COLUMN hold_proposal_withdrawn_at;
--   ALTER TABLE aml_cases DROP COLUMN hold_proposal_withdrawn_by;
--   ALTER TABLE aml_cases DROP COLUMN hold_proposal_expires_at;
--   ALTER TABLE aml_cases DROP COLUMN hold_proposer_person_id;
-- Leaving them is safe: the previous release does not map them. Data lost on dropping them: the
-- person binding, expiry and withdrawal record of proposals.
ALTER TABLE aml_cases ADD COLUMN hold_proposer_person_id VARCHAR(64);
ALTER TABLE aml_cases ADD COLUMN hold_proposal_expires_at TIMESTAMPTZ;
ALTER TABLE aml_cases ADD COLUMN hold_proposal_withdrawn_by VARCHAR(255);
ALTER TABLE aml_cases ADD COLUMN hold_proposal_withdrawn_at TIMESTAMPTZ;
