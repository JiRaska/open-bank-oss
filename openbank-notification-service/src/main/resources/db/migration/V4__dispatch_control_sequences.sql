-- ADR-0034 follow-up: Hibernate Reactive + PanacheEntity allocate IDs from a sequence named
-- "<table>_seq" with the default allocationSize of 50. V3 created the tables with BIGSERIAL
-- (which only yields "<table>_id_seq" + a column default Hibernate never uses), so every INSERT
-- failed with: relation "<table>_seq" does not exist. Create the sequences Hibernate expects.
-- Matches the repo convention (see party-service V6__fix_hibernate_sequences.sql): unquoted,
-- lowercase, INCREMENT BY 50. Rollback: DROP SEQUENCE dispatch_resume_proposal_seq, dispatch_control_log_seq;

CREATE SEQUENCE IF NOT EXISTS dispatch_control_log_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS dispatch_resume_proposal_seq INCREMENT BY 50;
