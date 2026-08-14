-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0257 rollback reconciliation. A P0 binary can update `products` while the additive v2 tables
-- remain present. The next current binary compares these watermarks with both authorities and
-- reconciles one-sided changes while rejecting divergent edits.
-- Rollback: retain both columns after any mixed-version write; older binaries ignore them.

ALTER TABLE bank_v1_product_mapping
    ADD COLUMN last_synced_product_revision BIGINT NOT NULL DEFAULT -1,
    ADD COLUMN last_synced_draft_revision BIGINT NOT NULL DEFAULT -2;
