-- Ongoing monitoring switch-on blockers (#16028), kyb side.
--
-- 1. Exit completion is four-eyes. One reviewer PROPOSES that the owed exit has been carried out,
--    and a DIFFERENT reviewer confirms it; only the confirmation ends monitoring. The proposal is
--    kept here, beside the V14 exit columns and for the same reason: kyb_cases.screening_json is
--    rewritten whole by every release, and a release that does not know these columns never
--    writes them (Hibernate updates only mapped columns), so neither a pod of the previous
--    release during a rolling deploy nor one after a rollback can drop a pending proposal.
--      monitoring_exit_proposed_at        when the pending (or the confirmed) proposal was made
--      monitoring_exit_proposed_by        who made it; the confirmer must be someone else
--      monitoring_exit_proposal_evidence  what was done and where the record of it is
--    All nullable: existing cases read back with no proposal.
--
-- 2. Re-assertion now covers WITHDRAWN rows too (a late PUT must not outlive the DELETE that
--    followed it). The V14 re-assertion index was partial on desired = 'REGISTERED'; this one
--    serves the widened read.
--
-- Rollback: DROP INDEX kyb_monitored_subjects_settled_idx;
--           ALTER TABLE kyb_cases DROP COLUMN monitoring_exit_proposed_at,
--                                 DROP COLUMN monitoring_exit_proposed_by,
--                                 DROP COLUMN monitoring_exit_proposal_evidence;
-- Rolling the APPLICATION back needs none of that: the previous release ignores the columns and
-- the index. Dropping the columns forgets a pending proposal, which a reviewer then makes again.
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_proposed_at TIMESTAMPTZ;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_proposed_by TEXT;
ALTER TABLE kyb_cases ADD COLUMN monitoring_exit_proposal_evidence TEXT;

CREATE INDEX kyb_monitored_subjects_settled_idx
    ON kyb_monitored_subjects (updated_at, id) WHERE sync_state <> 'PENDING';
