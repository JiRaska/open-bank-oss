-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- P3 / ADR-0257 compatibility bridge. The mapping is additive and keeps the canonical UUID owned
-- by `products`; a P0 binary ignores this table and remains a valid rollback path.
-- Rollback: stop P3 writers, then DROP TABLE bank_v1_product_mapping. The v1 products remain intact.

CREATE TABLE bank_v1_product_mapping (
    product_id UUID PRIMARY KEY REFERENCES products (id),
    default_offering_id UUID NOT NULL UNIQUE REFERENCES catalog_offerings (id),
    legacy_code VARCHAR(16),
    projected_revision_id UUID REFERENCES catalog_revisions (id),
    created_at TIMESTAMPTZ NOT NULL
);
