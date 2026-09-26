-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Supports bounded Customer 360 reads in deterministic recent-first order.
-- On a large live table, create this index CONCURRENTLY out of band before rolling Flyway;
-- transactional Flyway startup otherwise holds a write-blocking lock while building it.
-- Rollback: DROP INDEX IF EXISTS idx_device_tokens_party_recent;
CREATE INDEX IF NOT EXISTS idx_device_tokens_party_recent
    ON device_tokens (party_id, created_at DESC, id DESC);
