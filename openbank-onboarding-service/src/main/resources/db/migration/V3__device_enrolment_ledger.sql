-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors.
-- Flyway migration V3 (#6248): a per-credential ledger behind onboarding_records.device_count.
--
-- `device_count` was maintained as a blind `existing.deviceCount + 1` on every DEVICE_ENROLLED.
-- That makes the projection non-idempotent: replaying an event — which is the ONLY way the 15
-- enrolments already lost to #4353/#4692 can ever be recovered, since the Kafka topic's retention
-- has long since dropped them — inflates the count instead of converging. A read model must be
-- able to re-consume its own input.
--
-- Counting rows in this ledger instead makes DEVICE_ENROLLED idempotent by construction: the
-- unique key absorbs a duplicate, and `device_count` / `sca_enrolled` are then derived, never
-- incremented. `credential_id` is the sca-service credential identifier carried in the payload.
--
-- Rollback note:
--   DROP TABLE onboarding_device_enrolments;
--   DROP SEQUENCE onboarding_device_enrolments_seq;
-- `onboarding_records.device_count` keeps whatever value it holds, so a rollback degrades to the
-- previous (non-idempotent) behaviour rather than losing the funnel column.

CREATE TABLE IF NOT EXISTS onboarding_device_enrolments (
    id            BIGINT      PRIMARY KEY,
    party_id      UUID        NOT NULL,
    credential_id TEXT        NOT NULL,
    enrolled_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_onboarding_device_enrolment UNIQUE (party_id, credential_id)
);

CREATE INDEX IF NOT EXISTS idx_onboarding_device_enrolment_party
    ON onboarding_device_enrolments (party_id);

-- Hibernate (PanacheEntity, ORM 6) allocates ids from `<table>_seq` with a pooled optimizer of
-- allocationSize 50. V2 records what happens when that sequence is missing: every insert fails
-- with `relation "..._seq" does not exist` and the read model silently never persists.
CREATE SEQUENCE IF NOT EXISTS onboarding_device_enrolments_seq START WITH 1 INCREMENT BY 50;
