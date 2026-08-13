-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Commit-safe ordering for the standalone pull cursor. Rollback: stop cursor consumers, then drop
-- trg_catalog_outbox_commit_order, order_catalog_outbox_commits(), the cursor index/column and the
-- catalog_outbox_cursor_position_seq sequence. Never renumber after a cursor has been issued.

ALTER TABLE catalog_outbox ADD COLUMN cursor_position BIGINT;
CREATE SEQUENCE catalog_outbox_cursor_position_seq;

-- Backfill pre-cursor events deterministically while the ALTER TABLE lock excludes concurrent writes.
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS position
      FROM catalog_outbox
)
UPDATE catalog_outbox outbox
   SET cursor_position = ordered.position
  FROM ordered
 WHERE outbox.id = ordered.id;

SELECT setval(
    'catalog_outbox_cursor_position_seq',
    GREATEST(COALESCE((SELECT max(cursor_position) FROM catalog_outbox), 0), 1),
    EXISTS (SELECT 1 FROM catalog_outbox)
);

ALTER TABLE catalog_outbox ALTER COLUMN cursor_position SET NOT NULL;
CREATE UNIQUE INDEX idx_catalog_outbox_cursor_position ON catalog_outbox (cursor_position);

-- A normal BIGSERIAL default is allocated before a transaction commits and is therefore unsafe when
-- commits invert. Catalog mutations are low-volume governance operations, so serialize their outbox
-- insertion point. The xact lock remains held through commit; assigning the sequence inside this
-- trigger makes cursor_position both commit-safe and independent of wall-clock equality/regression.
CREATE FUNCTION order_catalog_outbox_commits() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(6200257);
    NEW.created_at = clock_timestamp();
    NEW.cursor_position = nextval('catalog_outbox_cursor_position_seq');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_catalog_outbox_commit_order
    BEFORE INSERT ON catalog_outbox
    FOR EACH ROW EXECUTE FUNCTION order_catalog_outbox_commits();

-- Cursor position is part of the durable event identity. Extend V5's immutable payload guard so a
-- maintenance UPDATE cannot move an event across an already persisted consumer watermark.
CREATE OR REPLACE FUNCTION enforce_catalog_outbox_payload_immutability() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.aggregate_type <> OLD.aggregate_type
        OR NEW.aggregate_id <> OLD.aggregate_id
        OR NEW.event_type <> OLD.event_type
        OR NEW.schema_version <> OLD.schema_version
        OR NEW.occurred_at <> OLD.occurred_at
        OR NEW.headers <> OLD.headers
        OR NEW.created_at <> OLD.created_at
        OR NEW.cursor_position <> OLD.cursor_position
        OR NEW.payload <> OLD.payload
    THEN
        RAISE EXCEPTION 'catalog outbox event % payload is immutable', OLD.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
