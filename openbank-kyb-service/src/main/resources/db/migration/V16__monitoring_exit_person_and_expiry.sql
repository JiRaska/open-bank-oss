-- Four-eyes on the monitoring exit compares PERSONS, not accounts (libs StaffIdentity): one natural
-- person with two staff accounts must not both propose and confirm the exit. The proposal also gets
-- a bounded lifetime and can be withdrawn by its proposer.
--   monitoring_exit_proposer_person_id      the proposer's person id (token claim person_id)
--   monitoring_exit_proposal_expires_at     after this it cannot be confirmed; a new one may replace it
--   monitoring_exit_proposal_withdrawn_by   account that withdrew it (always the proposer's person)
--   monitoring_exit_proposal_withdrawn_at   when
-- Nullable, no default: metadata-only. NO BACKFILL: inventing a person id or an expiry would
-- fabricate who proposed. A row of the V15 shape (no person id or no expiry) is inert under the new
-- rule (ExitProposal.isPersonBound): never open, so any reviewer may propose over it, and never
-- confirmable. Monitoring is not switched on anywhere, so V15 has never carried a real proposal.
--
-- Rollback: roll the code back first, then
--   ALTER TABLE kyb_cases DROP COLUMN monitoring_exit_proposal_withdrawn_at,
--                         DROP COLUMN monitoring_exit_proposal_withdrawn_by,
--                         DROP COLUMN monitoring_exit_proposal_expires_at,
--                         DROP COLUMN monitoring_exit_proposer_person_id;
-- Leaving them is safe: the previous release does not map them. Dropping them loses the person
-- binding, expiry and withdrawal record of exit proposals.
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_proposer_person_id VARCHAR(64);
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_proposal_expires_at TIMESTAMPTZ;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_proposal_withdrawn_by TEXT;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_proposal_withdrawn_at TIMESTAMPTZ;
