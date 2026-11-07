-- SPDX-License-Identifier: Apache-2.0
-- Additive shadow history only. No finding, approval, CI or deployment state is changed.
-- Rollback: disable sampling and roll back the application image; retain this additive table.
-- Destructive schema rollback (separate operator decision): DROP TABLE delivery_observations;
CREATE TABLE delivery_observations (
    id UUID PRIMARY KEY,
    repository VARCHAR(255) NOT NULL,
    slot_start TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    collector_version VARCHAR(255) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    snapshot_json JSONB NOT NULL CHECK (jsonb_typeof(snapshot_json) = 'object'),
    CONSTRAINT uq_delivery_repository_slot UNIQUE (repository, slot_start)
);
CREATE INDEX idx_delivery_observations_recent ON delivery_observations (repository, observed_at DESC);
