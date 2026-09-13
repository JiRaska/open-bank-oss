-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0252 synthetic taint on the outbox (fleet-wide shape). A dispatcher runs after the business
-- transaction, often on another worker, so request-scoped taint cannot survive the hand-off — the
-- persisted row is the boundary, and OutboxKafkaHeaders reconstructs the transport header from it.
--
-- A SEPARATE migration rather than a column in V1, matching every other outbox-bearing service:
-- `check-synthetic-outbox-taint.py` keys on this filename, and a service whose column exists only
-- inside a CREATE TABLE is invisible to the sweep that keeps the marker present fleet-wide. The
-- point of that sweep is that the column cannot quietly stop existing anywhere, and a service the
-- checker cannot see is exactly the gap it was built to close.
--
-- ROLLBACK: ALTER TABLE card_outbox DROP COLUMN synthetic;
-- Safe only while no synthetic traffic has been dispatched; after that the marker is the only
-- record of which events came from a bank-owned synthetic party.

ALTER TABLE card_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN card_outbox.synthetic IS
    'ADR-0252: the event was produced by a bank-owned synthetic party. Set from OutboxMessage.synthetic at write time.';
