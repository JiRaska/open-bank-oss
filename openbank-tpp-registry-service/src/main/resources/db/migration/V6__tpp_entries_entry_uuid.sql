-- SPDX-License-Identifier: Apache-2.0
-- The domain model's TppEntry.id is a UUID, but tpp_entries.id is a BIGSERIAL internal
-- primary key (V1__init.sql). TppRepositoryImpl.toDomain() bridged the gap with
-- UUID.fromString(id.toString()), which throws on any real row ("2" is not a UUID) — every
-- read of a registered TPP fails, so the eIDAS licence gate can never authorize anybody
-- (issue #2340). This column gives the domain id a real, stable value without touching the
-- existing BIGSERIAL primary key, which stays internal and is never exposed (no REST
-- resource, no openapi.yaml schema, no other service reads it).
ALTER TABLE tpp_entries ADD COLUMN entry_uuid UUID NOT NULL DEFAULT gen_random_uuid();
