-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

-- Natural-key idempotency for POST /api/v1/suppressions (ADR-0293, burn-down #8351). One ACTIVE
-- suppression per (party_id, scope, value): a retried create replays the original row instead of
-- stacking a second identical suppression. value is NULL exactly when scope='ALL' (see
-- suppressions_value_shape), and NULLs never conflict in a unique index, so the key coalesces it.
-- Partial over active rows only — a REVOKED suppression must not block a deliberate re-suppression
-- of the same value.
--
-- Rollback: DROP INDEX IF EXISTS uq_suppressions_active_natural;
-- (Reversible: dropping re-opens the duplicate-row window for concurrent retries; corrupts nothing.)
CREATE UNIQUE INDEX uq_suppressions_active_natural
    ON suppressions (party_id, scope, COALESCE(value, ''))
    WHERE revoked_at IS NULL;
