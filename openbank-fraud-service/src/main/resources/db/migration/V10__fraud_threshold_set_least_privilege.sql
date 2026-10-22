-- Least privilege on the ADR-0347 threshold-set table (follow-up to V9, #13457).
--
-- V9 ended with the service's historical idiom `GRANT ALL ON ALL TABLES IN SCHEMA public TO
-- openbank`. In every deployed and local environment the application role IS the database owner
-- (CNPG `owner: openbank`; Flyway migrates as that same role), so that GRANT changed nothing: an
-- owner already holds every privilege. What it did NOT do is the thing this table needs — the row
-- set is the audit record of who changed a fraud control and when, and the application has no
-- business deleting, truncating or rewriting the payload of any version.
--
-- So privileges are taken AWAY from the owner here, leaving exactly what the code issues:
--   SELECT                — active-set lookup, pending list, max live version
--   INSERT                — a new proposal (persist of a fresh id)
--   UPDATE (6 columns)    — the compare-and-set decision in FraudThresholdRepositoryImpl.DECIDE_HQL
-- and nothing else: no DELETE, no TRUNCATE, no UPDATE of version / thresholds_json /
-- content_hash / effective_from / proposed_by / proposed_at. A Postgres owner may revoke its own
-- privileges and the check then applies to its own sessions.
--
-- Scope of the guarantee: this stops the application (a bug, a careless future query, an HQL
-- injection) from erasing or rewriting the record. It does NOT stop a principal able to run
-- arbitrary SQL as the owner, which can re-GRANT to itself or DROP the table — those remain owner
-- rights. Separating a migration role from the runtime role is the control for that, out of scope.
--
-- Grantee. Flyway and the runtime share one role in every environment today: both read the
-- default datasource credentials from the CNPG `fraud-db-app` secret (the owner `openbank`), and no
-- QUARKUS_FLYWAY_USERNAME exists. The statements target CURRENT_USER (the migrating role, so the
-- file is not tied to a role name), and repeat for the literal `openbank` that V9 granted to, if
-- that role exists and is a different one, so a later split of migration and runtime roles cannot
-- leave V9's grant standing on the runtime side.
--
-- V9 is deliberately not edited: Flyway checksums applied migrations and V9 reaches every
-- environment with the next deploy.
--
-- Rollback (restores V9's effective state; no data touched):
--   GRANT ALL ON fraud_threshold_set TO CURRENT_USER;
--
-- A later migration that must rewrite other columns of this table grants that column explicitly.
-- Do NOT end a later migration with the old schema-wide `GRANT ALL ON ALL TABLES IN SCHEMA public`
-- idiom: it would silently re-grant DELETE/TRUNCATE here (FraudThresholdSetPrivilegeIT catches it).

REVOKE ALL ON fraud_threshold_set FROM PUBLIC;
REVOKE ALL ON fraud_threshold_set FROM CURRENT_USER;

GRANT SELECT, INSERT ON fraud_threshold_set TO CURRENT_USER;
GRANT UPDATE (state, decided_by, decided_at, decision_reason, active_from, updated_at)
    ON fraud_threshold_set TO CURRENT_USER;

DO $$
BEGIN
    IF current_user <> 'openbank' AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'openbank') THEN
        REVOKE ALL ON fraud_threshold_set FROM openbank;
        GRANT SELECT, INSERT ON fraud_threshold_set TO openbank;
        GRANT UPDATE (state, decided_by, decided_at, decision_reason, active_from, updated_at)
            ON fraud_threshold_set TO openbank;
    END IF;
END
$$;
