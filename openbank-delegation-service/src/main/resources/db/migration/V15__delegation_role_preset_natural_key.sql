-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- Natural-key idempotency for POST /api/v1/delegation-role-presets (ADR-0292, burn-down #8351):
-- one preset per (name, resource_type). A retried admin create replays the original preset instead
-- of stacking a duplicate catalog row. The service checks the key first; this index is the race
-- backstop for two concurrent first attempts.
--
-- Rollback: DROP INDEX IF EXISTS uq_delegation_role_presets_name_type;
-- (Reversible: dropping re-opens the duplicate-name window; corrupts nothing.)
CREATE UNIQUE INDEX uq_delegation_role_presets_name_type
    ON delegation_role_presets (name, resource_type);
