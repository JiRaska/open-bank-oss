-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0072: blind index for Czech RČ dedup (1 person = 1 party).
--
-- rc_blind_index  = HMAC-SHA256(pepper, canonical_rc) as lowercase hex (64 chars).
--                   UNIQUE ensures a second onboarding with the same RČ is rejected
--                   before a duplicate party record is created.
--                   NULL for non-Czech nationals and parties without an RČ taxId.
--
-- rc_index_key_version = which pepper version was used; needed for pepper rotation
--                        re-index migrations (pepper is versioned, never re-used).
--
-- Rollback note: DROP COLUMN is instant in Postgres (no-rewrite, logical catalog change).
ALTER TABLE parties
    ADD COLUMN rc_blind_index      CHAR(64) UNIQUE,
    ADD COLUMN rc_index_key_version INT;
