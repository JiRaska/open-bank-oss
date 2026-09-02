-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Published catalog revisions are external reference data for a money-path service.  Keep the
-- consumed immutable content hash and the event acknowledgement local, so an accrual can resolve
-- a durable effective-dated rate without calling the catalog at calculation time.  The change is
-- purely additive: older binaries neither read nor write these tables and continue using their
-- existing operator-managed rate configurations during a rolling rollback.

-- V1 stored an annual rate at scale six. Catalog v2 declares exact values through scale eighteen;
-- widening is backward-compatible and preserves every existing value exactly.
ALTER TABLE interest_rate_configs
    ALTER COLUMN annual_rate TYPE NUMERIC(20,18);

CREATE TABLE catalog_interest_sync_state (
    consumer    VARCHAR(64) PRIMARY KEY,
    cursor      TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE catalog_interest_event_receipts (
    event_id      UUID PRIMARY KEY,
    event_type    VARCHAR(128) NOT NULL,
    outcome       VARCHAR(32) NOT NULL CHECK (outcome IN ('APPLIED', 'SKIPPED', 'REJECTED')),
    reason        VARCHAR(512),
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE catalog_interest_rate_snapshots (
    revision_id       UUID PRIMARY KEY,
    offering_id       UUID NOT NULL,
    specification_id  UUID NOT NULL,
    config_id         UUID REFERENCES interest_rate_configs(id),
    schema_id         VARCHAR(128) NOT NULL,
    schema_version    INTEGER NOT NULL,
    content_hash      CHAR(64) NOT NULL,
    currency          CHAR(3),
    annual_rate       NUMERIC(20,18),
    day_count         VARCHAR(16),
    effective_from    DATE,
    effective_to      DATE,
    outcome           VARCHAR(32) NOT NULL CHECK (outcome IN ('APPLIED', 'REJECTED')),
    reason            VARCHAR(512),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (
        (outcome = 'APPLIED' AND config_id IS NOT NULL AND currency IS NOT NULL AND annual_rate IS NOT NULL
            AND day_count IS NOT NULL AND effective_from IS NOT NULL)
        OR
        (outcome = 'REJECTED' AND config_id IS NULL)
    ),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_catalog_interest_snapshots_specification
    ON catalog_interest_rate_snapshots(specification_id, currency, effective_from DESC);

-- Rollback:
--   ALTER TABLE interest_rate_configs ALTER COLUMN annual_rate TYPE NUMERIC(10,6);
--   DROP TABLE catalog_interest_rate_snapshots;
--   DROP TABLE catalog_interest_event_receipts;
--   DROP TABLE catalog_interest_sync_state;
-- The annual-rate narrowing is safe only if no scale > 6 value has been imported. The additive
-- tables themselves do not affect pre-V14 readers/writers, which remain readable/writable during
-- an expand rollout. Retain the data in production rather than executing this rollback.
