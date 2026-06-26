-- Hibernate Reactive + PanacheEntity allocate IDs from a sequence named "<table>_seq" with the
-- default allocationSize of 50. V1/V2 created the notifications and notification_outbox tables with
-- BIGSERIAL (which only yields "<table>_id_seq" + a column default Hibernate never uses), so every
-- INSERT would fail with: relation "<table>_seq" does not exist. This is the same latent defect that
-- V4 fixed for the dispatch_control_* tables (#109); the notification write path simply had not been
-- exercised against a real Postgres yet (inbound writes arrive via Kafka).
-- Matches the repo convention (see party-service V6__fix_hibernate_sequences.sql): unquoted,
-- lowercase, INCREMENT BY 50. Rollback: DROP SEQUENCE notification_outbox_seq, notifications_seq;

CREATE SEQUENCE IF NOT EXISTS notifications_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS notification_outbox_seq INCREMENT BY 50;
