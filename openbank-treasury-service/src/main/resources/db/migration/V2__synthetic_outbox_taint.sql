-- SPDX-License-Identifier: Apache-2.0
-- ADR-0252: a synthetic request's events must stay identifiable across the outbox hand-off. The
-- persisted row IS the boundary — OutboxKafkaHeaders reconstructs the transport header from it —
-- so a missing column silently launders a synthetic event into a real-looking one.
-- Safe default FALSE: every pre-existing row is real traffic.
-- Rollback: ALTER TABLE treasury_outbox DROP COLUMN synthetic;

ALTER TABLE treasury_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
