-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0105 P1: persist the product catalogue (was an in-memory ConcurrentHashMap seed).
-- The schema is document-shaped — the full Product is stored as JSONB `doc`, with identity and
-- filter attributes promoted to indexed scalar columns. Three identifiers (ADR-0105): `id` is the
-- durable canonical UUID account-service references; `code` is the semantic code; `legacy_code` is
-- the prod-NNN alias. The 15 canonical products are seeded idempotently on first boot from the
-- Kotlin ProductSeed (single source of truth), keyed by their canonical UUIDs.
--
-- Rollback: DROP TABLE products;

CREATE TABLE products (
    id           UUID         PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    legacy_code  VARCHAR(16)  UNIQUE,
    type         VARCHAR(24)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    currency     VARCHAR(3)   NOT NULL,
    doc          JSONB        NOT NULL
);

CREATE INDEX idx_products_type_status ON products (type, status);
