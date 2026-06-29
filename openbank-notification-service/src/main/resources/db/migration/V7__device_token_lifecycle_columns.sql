-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

-- ADR-0135 §2 — Push-token lifecycle audit columns.
-- registered_at: when the party first bound this device token (immutable; set once on creation).
-- refreshed_at:  when the mobile app last called POST /api/v1/devices (foreground refresh).
--   The 90-day stale sweep (DeviceTokenSweepJob) uses refreshed_at when non-null; falls back
--   to last_used_at for rows migrated from V6 that were never explicitly refreshed post-deploy.
--
-- Rollback: ALTER TABLE device_tokens DROP COLUMN registered_at, DROP COLUMN refreshed_at;
ALTER TABLE device_tokens ADD COLUMN registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE device_tokens ADD COLUMN refreshed_at  TIMESTAMPTZ;

-- Backfill from existing data so existing rows are non-null.
UPDATE device_tokens SET registered_at = created_at, refreshed_at = last_used_at;
