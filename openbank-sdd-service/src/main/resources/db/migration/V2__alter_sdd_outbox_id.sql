-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
-- See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
--
-- Migrate sdd_outbox.id from UUID to BIGSERIAL to align with PanacheOutboxEntity.
-- Existing rows are dropped: the outbox is a transient processing queue; in-flight
-- rows at migration time are replayed from the source aggregate on restart.
-- Rollback: DROP SEQUENCE sdd_outbox_seq;
--           ALTER TABLE sdd_outbox DROP COLUMN id;
--           ALTER TABLE sdd_outbox ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
--           ALTER TABLE sdd_outbox ADD CONSTRAINT pk_sdd_outbox PRIMARY KEY (id);
ALTER TABLE sdd_outbox DROP CONSTRAINT pk_sdd_outbox;
ALTER TABLE sdd_outbox DROP COLUMN id;
ALTER TABLE sdd_outbox ADD COLUMN id BIGSERIAL PRIMARY KEY;
-- BIGSERIAL only creates {table}_id_seq; Hibernate Reactive (PanacheEntity) allocates IDs
-- from a sequence named {table}_seq with allocationSize=50 — without this the first INSERT
-- fails with "relation sdd_outbox_seq does not exist" (same defect class as account V9).
CREATE SEQUENCE IF NOT EXISTS sdd_outbox_seq INCREMENT BY 50;
