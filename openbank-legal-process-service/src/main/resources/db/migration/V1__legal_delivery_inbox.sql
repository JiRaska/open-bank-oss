-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- This inbox contains unverified submissions. It publishes no legal effects.
-- Rollback: roll back the writer independently. Keep this additive table and its
-- evidence pointers under the legal retention policy. Dropping it would lose receipts.
CREATE TABLE legal_delivery_inbox (
    receipt_id UUID PRIMARY KEY,
    source_namespace VARCHAR(128) NOT NULL,
    delivery_id VARCHAR(160) NOT NULL,
    route_country CHAR(2) NOT NULL CHECK (route_country IN ('CZ', 'SK', 'DE')),
    evidence_id UUID NOT NULL,
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (source_namespace, delivery_id)
);
