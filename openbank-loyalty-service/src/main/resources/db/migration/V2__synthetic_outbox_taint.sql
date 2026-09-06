-- ADR-0252: preserve synthetic-origin taint across asynchronous outbox dispatch.
--
-- A separate migration rather than a column in V1's CREATE TABLE, deliberately. The fleet records
-- this column in a file named `*__synthetic_outbox_taint.sql`, and `check-synthetic-outbox-taint.py`
-- reads that filename to establish the property holds — a new service that folded the column into
-- its CREATE TABLE would be invisible to the check and flagged as missing it. Following the
-- convention keeps the guard meaningful for this service instead of making it argue with it.
--
-- Rollback: before this migration is applied, DROP COLUMN synthetic; never edit an applied Flyway migration.
ALTER TABLE loyalty_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
