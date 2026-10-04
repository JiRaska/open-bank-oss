-- The 750 ms statement backstop used to be a cluster-wide postgresql parameter on context-db.
-- That also bound the postgres superuser, and barman-cloud-backup's pg_backup_stop() on the
-- primary (which waits for WAL archiving) was cancelled by it, so every primary base backup
-- failed. Keep the backstop for the application role in this database only; operator and
-- backup sessions are no longer subject to it. Takes effect for new sessions.
-- current_user is the application role: Flyway migrates the default datasource, whose user is the
-- same one the service runs as (quarkus.datasource.username; no separate flyway user is set). It is
-- the CNPG database owner (NOSUPERUSER, NOCREATEROLE), which may set its own per-database defaults.
-- Rollback: ALTER ROLE <app role> IN DATABASE <db> RESET statement_timeout; and restore the
-- cluster parameter in openbank-infra/gitops/components/context/postgres.yaml.
DO $$
BEGIN
  EXECUTE format('ALTER ROLE %I IN DATABASE %I SET statement_timeout = %L',
                 current_user, current_database(), '750ms');
END
$$;
