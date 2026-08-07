-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- A campaign may declare a product event that enrols a party the moment it happens, instead of
-- waiting for the next manual enrol or scheduled sweep. A TriggerCatalog key, not a topic name and
-- not a filter expression: the topic, the accepted event types and the matching rule live in domain
-- code, so a trigger is reviewed and diffable, and one naming a stream nobody publishes cannot be
-- stored.
--
-- The trigger decides WHEN. The segment still decides WHO — an event only enrols a party the
-- campaign's segment currently contains, so a trigger is never a way past the approved audience.
--
-- Nullable: every existing campaign has none and behaves exactly as before, enrolling on POST
-- /{id}/enrol or on its cadence.
--
-- Rollback: ALTER TABLE campaigns DROP COLUMN trigger_event;
-- Note "trigger" is a reserved word in SQL, hence the column name.
ALTER TABLE campaigns ADD COLUMN trigger_event varchar(64);
