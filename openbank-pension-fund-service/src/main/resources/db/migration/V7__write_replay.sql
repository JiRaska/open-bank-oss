-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Durable response snapshots for scoped, fingerprinted fund API writes. The receipt and local
-- business mutation commit together. No TTL or automatic cleanup can reopen a completed key.
-- key_hash is SHA-256 of IdempotencyScope.storeKey; request_hash binds method, path and canonical DTO.
-- Response snapshots may contain audit personal data. Hashes are pseudonymous, not anonymization;
-- apply the service's access controls and retention review to this table as to the source records.
CREATE TABLE pension_fund_write_replay (
    key_hash       VARCHAR(64) PRIMARY KEY CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    request_hash   VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status_code    INTEGER NOT NULL CHECK (status_code BETWEEN 100 AND 599),
    response_json  TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Rollback: roll back the application while retaining receipts and their response snapshots.
-- Dropping or expiring receipts permits duplicate writes after retries and destroys audit history.
-- Any schema removal or retention change requires a separate reviewed preservation plan.
